package com.hotspot.billing.core

import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.net.ApHandle
import com.hotspot.billing.net.ApLauncher
import com.hotspot.billing.net.ApMode
import com.hotspot.billing.net.LanPlan
import com.hotspot.billing.net.SoftApController
import com.hotspot.billing.util.RootShell
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * Phase 1 — Fix Hotspot Engine
 * NetworkController / NetworkEngine
 *
 * Responsibilities:
 * START:
 *  Detect internet source
 *  Create LAN
 *  Start DHCP
 *  Start DNS
 *  Enable NAT
 *  Test internet
 *  Return SUCCESS
 *
 * STOP:
 *  Clean shutdown: DHCP, DNS, NAT, Hotspot
 *
 * This is the foundation — without stable DHCP/NAT/DNS, vouchers and printing
 * will only sit on top of a broken hotspot.
 */
class NetworkController(
    private val apLauncher: ApLauncher
) {

    sealed class StartResult {
        data class Success(
            val wanInfo: WanDetector.WanInfo,
            val lanIf: String,
            val apHandle: ApHandle?,
            val lanPlan: LanPlan?,
            val dhcpConfig: DhcpManager.DhcpConfig
        ) : StartResult()

        data class Failed(val reason: String, val step: Step) : StartResult()
    }

    private companion object {
        /** The script assigns a fallback address itself, so this is a short wait. */
        const val ADDRESS_WAIT_MS = 4_000L
    }

    enum class Step {
        WAN_DETECTION,
        LAN_CREATION,
        ADDRESS_WAIT,
        DHCP_START,
        DNS_START,
        NAT_ENABLE,
        INTERNET_TEST
    }

    /**
     * START button flow.
     *
     * Ordering is decided by what actually depends on what:
     *
     *   1. WAN detect            (one command, ~300 ms)
     *   2. LAN AP                (WiFi Direct / LOHS / hostapd - the slow part)
     *   3. address               (usually already there: the script adopts it)
     *   4/5. DHCP+DNS and NAT    run TOGETHER: they are independent, and one
     *                            `setup_network.sh start` does both anyway
     *   6. one probe             verifies everything in a single root command
     *   7. internet test + portal
     *
     * The old flow was six strictly sequential steps, each with its own wait and
     * its own pile of verification commands: the 2026-09-24 log shows 6 s to the
     * AP, then 25 s in DHCP, 15 s in DNS, 31 s in NAT, 20 s in the internet test
     * - 74 s of a 80 s start, all of it spent waiting on the shell.
     *
     * Every phase is logged with its duration so the next debug export shows
     * where the time actually went.
     */
    suspend fun start(
        mode: ApMode,
        ssid: String,
        password: String,
        pinnedLanIf: String?,
        log: (String) -> Unit
    ): StartResult {
        val startAll = System.currentTimeMillis()
        log("network: START requested mode=${mode.label} ssid=$ssid")

        // 1. Detect the internet side ONCE, and pin it for the script. Two
        //    independent "auto" resolutions is how NAT ended up on ccmni1 while
        //    the traffic left through ccmni0 (2026-09-24, 12:37:25 vs 12:37:52):
        //    the client could redeem a voucher and still get no internet.
        val tWan = System.currentTimeMillis()
        val wan = WanDetector.detectWan()
        if (wan == null) {
            val msg = "No internet source found — check mobile data or WiFi"
            log("network: FAILED WAN detection: $msg")
            return StartResult.Failed(msg, Step.WAN_DETECTION)
        }
        if (!wan.hasInternet) {
            AppLog.w(AppLog.TAG_NET, "network: WAN ${wan.interfaceName} has no IP, continuing anyway (may recover)")
        }
        log("network: WAN = ${wan.interfaceName} (${wan.type}) hasInternet=${wan.hasInternet} (${ms(tWan)})")

        // 2. Create LAN (AP)
        val tAp = System.currentTimeMillis()
        log("network: creating LAN AP")
        var apHandle: ApHandle? = null
        var lanIf: String? = null

        try {
            apHandle = apLauncher.launch(mode, ssid, password, pinnedLanIf, log)
            lanIf = apHandle?.interfaceName
        } catch (e: Throwable) {
            log("network: AP launcher threw ${e.javaClass.simpleName}: ${e.message}")
            AppLog.e(AppLog.TAG_AP, "network: launcher failed", e)
        }

        if (lanIf == null) {
            log("network: no interface from launcher, waiting for AP...")
            lanIf = waitForLanInterface(pinnedLanIf, 5_000, log)
        }

        if (lanIf == null) {
            val msg = "No hotspot interface appeared. WiFi and Location were switched on and " +
                "every method in ${mode.label} was tried (see the debugger for the reason each one failed). " +
                "The gateway retries; switching the Android hotspot on still works."
            log("network: FAILED LAN creation: $msg (${ms(tAp)})")
            return StartResult.Failed(msg, Step.LAN_CREATION)
        }
        log("network: LAN = $lanIf (AP ${ms(tAp)})")

        // Pin both interfaces for every later script call (keepalive, stop, nat).
        writeInterfaceEnv(lanIf, wan.interfaceName, log)

        // 3. Address. The script adopts whatever Android already assigned and
        //    only creates one if there is none, so this is normally instant.
        val tAddr = System.currentTimeMillis()
        val address = waitForAddressFast(lanIf, ADDRESS_WAIT_MS, log)
        if (address == null) {
            AppLog.w(AppLog.TAG_NET, "network: no address on $lanIf after ${ADDRESS_WAIT_MS}ms, continuing")
        }
        log("network: address on $lanIf = ${address ?: "none"} (${ms(tAddr)})")

        // 4./5. DHCP+DNS and NAT: independent, therefore concurrent. The DHCP
        //       call is the same `setup_network.sh start` that installs the
        //       firewall, the redirect and the masquerade; the NAT task only
        //       re-asserts the uplink-specific parts right after.
        val tConfigure = System.currentTimeMillis()
        val leaveDhcp = apHandle?.leaveAndroidDhcp == true || apLauncher.shouldLeaveAndroidDhcp(lanIf)
        if (leaveDhcp) {
            log("network: Android owns DHCP on $lanIf - not replacing its dnsmasq")
        }
        val lan = lanIf
        val (dhcpOk, natOk) = coroutineScope {
            val dhcp = async { DhcpManager.start(lan, leaveDhcp) }
            val nat = async { RootShell.repairNat(lan, wan.interfaceName, subnetFor(lan)) }
            dhcp.await() to nat.await()
        }
        if (!natOk) {
            AppLog.w(AppLog.TAG_NET, "network: forwarding repair returned false on $lan, continuing")
        }
        if (!dhcpOk) {
            val msg = "DHCP failed to start on $lan — clients will stuck at Obtaining IP"
            log("network: FAILED DHCP: $msg")
            if (!DhcpManager.restart(lan)) {
                return StartResult.Failed(msg, Step.DHCP_START)
            }
        }
        val dhcpConfig = DhcpManager.getConfig(lan)
            ?: return StartResult.Failed(
                "DHCP is running, but no LAN plan/config could be read on $lan — clients may not get a gateway",
                Step.DHCP_START
            )

        // 6. One probe verifies DHCP, DNS, NAT, the firewall and policy routing.
        val probe = RootShell.probe(lan, fresh = true)
        val dnsInfo = DnsManager.getInfo()
        if (probe != null && probe.dhcpOurs == false && probe.dhcpOrphan == false && probe.dhcpForeign == false) {
            val msg = "no DHCP server is answering on $lan after start"
            log("network: FAILED DNS/DHCP: $msg")
            return StartResult.Failed(msg, Step.DNS_START)
        }
        if (probe?.masquerade == false) {
            log(
                "network: WARNING no MASQUERADE for ${probe.subnet} on ${wan.interfaceName} - " +
                    "clients would get an IP and no internet"
            )
        }
        log(
            "network: configured in ${ms(tConfigure)} - dhcp=${DhcpManager.owner() ?: "?"} " +
                "gateway=${dhcpConfig.gateway} range=${dhcpConfig.startIp}-${dhcpConfig.endIp} " +
                "dns=${dnsInfo.isRunning}/${dnsInfo.foreignRunning} " +
                "nat=${probe?.natJump} fwd=${probe?.forwardJump} masq=${probe?.masquerade} " +
                "routing=${probe?.ruleIif}/${probe?.ruleSubnet}"
        )

        // 7. Internet test (informational: a transient failure must not stop the gateway).
        val tNet = System.currentTimeMillis()
        log("network: testing internet connectivity")
        val internetOk = NatManager.checkInternet()
        if (!internetOk) {
            AppLog.w(AppLog.TAG_NET, "network: internet test failed, but AP is up — may be transient")
        }
        log("network: internet test ${if (internetOk) "passed" else "failed"} (${ms(tNet)})")

        val plan = RootShell.readLanPlan()
        log(
            "network: START SUCCESS WAN=${wan.interfaceName} LAN=$lan " +
                "gateway=${plan?.gateway} dhcp=${dhcpConfig.startIp}-${dhcpConfig.endIp} " +
                "TOTAL ${ms(startAll)}"
        )

        return StartResult.Success(
            wanInfo = wan,
            lanIf = lan,
            apHandle = apHandle,
            lanPlan = plan,
            dhcpConfig = dhcpConfig
        )
    }

    /**
     * Polls for an AP interface when the launcher returned no handle (a method
     * that brought the AP up without telling us its name, or a pinned interface
     * in the settings). 250 ms, not a fixed wait: on a good start the first poll
     * already answers.
     */
    private suspend fun waitForLanInterface(
        pinnedLanIf: String?,
        timeoutMs: Long,
        log: (String) -> Unit
    ): String? {
        val started = System.currentTimeMillis()
        var lastLogged = ""
        while (System.currentTimeMillis() - started < timeoutMs) {
            val iface = try {
                SoftApController.apInterface(pinnedLanIf)
            } catch (e: Throwable) {
                null
            }
            if (iface != null) return iface
            if (lastLogged.isEmpty()) {
                lastLogged = "waiting"
                log("network: no AP interface yet, polling every 250ms")
            }
            delay(250)
        }
        return null
    }

    private fun ms(since: Long): String = "${System.currentTimeMillis() - since}ms"

    /** The subnet the script is actually using (adopted address or the default). */
    private fun subnetFor(lanIf: String): String {
        RootShell.readLanPlan()?.let { return it.subnet }
        val addr = RootShell.lanAddresses(lanIf).firstOrNull()
        val ip = addr?.substringBefore('/')
        return if (ip != null && ip.count { it == '.' } == 3) {
            "${ip.substringBeforeLast('.')}.0/24"
        } else {
            DhcpManager.DEFAULT_SUBNET
        }
    }

    /**
     * The script's start() adopts the address Android already put on the
     * interface (and assigns one if there is none), so the app only has to wait
     * for it to appear. Polling every 250 ms instead of a fixed multi-second wait
     * is what removes this from the critical path.
     */
    private suspend fun waitForAddressFast(lanIf: String, timeoutMs: Long, log: (String) -> Unit): String? {
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < timeoutMs) {
            val addr = try {
                RootShell.lanAddresses(lanIf).firstOrNull()
            } catch (e: Throwable) {
                null
            }
            if (addr != null) return addr
            delay(250)
        }
        return null
    }

    /** Writes LAN_IF/WAN_IF into hotspot.env so the script never has to guess. */
    private fun writeInterfaceEnv(lanIf: String, wanIf: String, log: (String) -> Unit) {
        try {
            RootShell.writeEnvFile(linkedMapOf("LAN_IF" to lanIf, "WAN_IF" to wanIf))
            log("network: pinned hotspot.env LAN_IF=$lanIf WAN_IF=$wanIf")
        } catch (e: IllegalArgumentException) {
            log("network: hotspot.env NOT written - ${e.message}")
        }
    }

    /**
     * STOP button flow — clean shutdown.
     *
     * The whole teardown is one script call (`setup_network.sh stop`, which also
     * stops the shaper), then the AP handle is released. The expensive verification
     * is a single probe, and it is advisory: a leftover is reported, not retried
     * for minutes.
     */
    suspend fun stop(
        apHandle: ApHandle?,
        log: (String) -> Unit
    ) {
        val started = System.currentTimeMillis()
        log("network: STOP requested")

        val stopped = try {
            RootShell.stopNetwork().isSuccess
        } catch (e: Throwable) {
            AppLog.w(AppLog.TAG_NET, "network: stop command failed: ${e.message}")
            false
        }
        log("network: DHCP/DNS/NAT/shaper removed (script exit ok=$stopped, ${ms(started)})")

        apHandle?.let {
            log("network: releasing AP ${it.kind.label} ${it.interfaceName}")
            it.close(log)
        }

        val probe = RootShell.probe(fresh = true)
        if (probe != null) {
            val leftover = buildList {
                if (probe.natJump == true) add("HS_NAT jump")
                if (probe.forwardJump == true) add("HS_FWD jump")
                if (probe.dhcpOurs == true) add("our dnsmasq")
                if (probe.dhcpOrphan == true) add("an untracked dnsmasq of ours")
                if (probe.masquerade == true) add("masquerade")
            }
            if (leftover.isEmpty()) {
                log("network: STOP clean - nothing of ours is left (${ms(started)} total)")
            } else {
                AppLog.w(
                    AppLog.TAG_NET,
                    "network: STOP left ${leftover.joinToString()} behind - " +
                        "the EXIT button runs the emergency cleaner"
                )
            }
        } else {
            log("network: STOP completed (${ms(started)} total)")
        }
    }
}
