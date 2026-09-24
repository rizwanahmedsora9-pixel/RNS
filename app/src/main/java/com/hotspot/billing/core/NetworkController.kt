package com.hotspot.billing.core

import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.net.ApHandle
import com.hotspot.billing.net.ApLauncher
import com.hotspot.billing.net.ApMode
import com.hotspot.billing.net.LanPlan
import com.hotspot.billing.util.RootShell
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
     * START button flow as per master plan.
     */
    suspend fun start(
        mode: ApMode,
        ssid: String,
        password: String,
        pinnedLanIf: String?,
        log: (String) -> Unit
    ): StartResult {
        log("network: START requested mode=${mode.label} ssid=$ssid")

        // 1. Detect internet source
        log("network: step 1/6 detecting WAN")
        val wan = WanDetector.detectWan()
        if (wan == null) {
            val msg = "No internet source found — check mobile data or WiFi"
            log("network: FAILED WAN detection: $msg")
            return StartResult.Failed(msg, Step.WAN_DETECTION)
        }
        if (!wan.hasInternet) {
            AppLog.w(AppLog.TAG_NET, "network: WAN ${wan.interfaceName} has no IP, continuing anyway (may recover)")
        }
        log("network: WAN = ${wan.interfaceName} (${wan.type}) hasInternet=${wan.hasInternet}")

        // 2. Create LAN (AP)
        log("network: step 2/6 creating LAN AP")
        var apHandle: ApHandle? = null
        var lanIf: String? = null

        try {
            // Try launcher
            apHandle = apLauncher.launch(mode, ssid, password, pinnedLanIf, log)
            lanIf = apHandle?.interfaceName
        } catch (e: Throwable) {
            log("network: AP launcher threw ${e.javaClass.simpleName}: ${e.message}")
            AppLog.e(AppLog.TAG_AP, "network: launcher failed", e)
        }

        // If launcher didn't give us interface, wait for any AP interface
        if (lanIf == null) {
            log("network: no interface from launcher, waiting for AP...")
            lanIf = waitForLanInterface(30_000, log)
        }

        if (lanIf == null) {
            val msg = "No hotspot interface appeared. WiFi and Location were switched on and " +
                "every method in ${mode.label} was tried (see the debugger for the reason each one failed). " +
                "The gateway retries; switching the Android hotspot on still works."
            log("network: FAILED LAN creation: $msg")
            return StartResult.Failed(msg, Step.LAN_CREATION)
        }
        log("network: LAN = $lanIf")

        // 3. Wait for address (adopt Android's address, don't force)
        log("network: step 3/6 waiting for address on $lanIf")
        val address = apLauncher.waitForAddress(lanIf, 15_000, log)
        if (address == null) {
            AppLog.w(AppLog.TAG_NET, "network: no address on $lanIf after 15s, continuing (may still work)")
        }

        // 4. Start DHCP (must guarantee client gets IP, gateway, DNS)
        log("network: step 4/6 starting DHCP on $lanIf")
        val leaveDhcp = apHandle?.leaveAndroidDhcp == true ||
            apLauncher.shouldLeaveAndroidDhcp(lanIf)
        if (leaveDhcp) {
            log("network: Android owns DHCP on $lanIf - not replacing its dnsmasq")
        }
        val dhcpOk = DhcpManager.start(lanIf, leaveDhcp)
        if (!dhcpOk) {
            val msg = "DHCP failed to start on $lanIf — clients will stuck at Obtaining IP"
            log("network: FAILED DHCP: $msg")
            // Try granular repair once
            if (!DhcpManager.restart(lanIf)) {
                return StartResult.Failed(msg, Step.DHCP_START)
            }
        }
        val dhcpConfig = DhcpManager.getConfig(lanIf)
            ?: return StartResult.Failed(
                "DHCP is running, but no LAN plan/config could be read on $lanIf — clients may not get a gateway",
                Step.DHCP_START
            )
        log("network: DHCP config gateway=${dhcpConfig.gateway} range=${dhcpConfig.startIp}-${dhcpConfig.endIp}")

        // 5. Start DNS (same dnsmasq, but verify)
        log("network: step 5/6 starting DNS")
        val dnsInfo = DnsManager.getInfo()
        if (!dnsInfo.isRunning) {
            val msg = "DNS not running after DHCP start"
            log("network: FAILED DNS: $msg")
            if (!DnsManager.repair(lanIf)) {
                return StartResult.Failed(msg, Step.DNS_START)
            }
        }
        log("network: DNS running gateway=${dnsInfo.gateway} upstream=${dnsInfo.upstream1},${dnsInfo.upstream2} foreign=${dnsInfo.foreignRunning}")

        // 6. Enable NAT
        log("network: step 6/6 enabling NAT WAN=${wan.interfaceName} LAN=$lanIf")
        val plan = RootShell.readLanPlan()
        val subnet = plan?.subnet ?: "10.66.0.0/24"
        val natOk = NatManager.enableNat(wan.interfaceName, lanIf, subnet)
        if (!natOk) {
            AppLog.w(AppLog.TAG_NET, "network: NAT enable returned false, but continuing")
        }

        // 7. Test internet (verify clients will get internet)
        log("network: testing internet connectivity")
        val internetOk = NatManager.checkInternet()
        if (!internetOk) {
            AppLog.w(AppLog.TAG_NET, "network: internet test failed, but AP is up — may be transient")
            // Don't fail hard here, WAN may recover
        }

        log("network: START SUCCESS WAN=${wan.interfaceName} LAN=$lanIf gateway=${plan?.gateway} dhcp=${dhcpConfig.startIp}-${dhcpConfig.endIp}")

        return StartResult.Success(
            wanInfo = wan,
            lanIf = lanIf,
            apHandle = apHandle,
            lanPlan = plan,
            dhcpConfig = dhcpConfig
        )
    }

    /**
     * STOP button flow — clean shutdown.
     */
    suspend fun stop(
        apHandle: ApHandle?,
        log: (String) -> Unit
    ) {
        log("network: STOP requested")

        // 1. Stop DHCP/DNS
        log("network: stopping DHCP/DNS")
        DhcpManager.stop()

        // 2. Disable NAT
        log("network: disabling NAT")
        NatManager.disableNat()

        // 3. Close AP handle (releases LOHS reservation / P2P group)
        apHandle?.let {
            log("network: releasing AP ${it.kind.label} ${it.interfaceName}")
            it.close(log)
        }

        // 4. Stop root hostapd if running
        try {
            RootShell.run("sh /data/local/tmp/netshare_ap.sh stop", quiet = false)
        } catch (e: Throwable) {
            // ignore
        }

        log("network: STOP completed")
    }

    private suspend fun waitForLanInterface(timeoutMs: Long, log: (String) -> Unit): String? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            val lan = WanDetector.detectLanInterfaces().firstOrNull()
            if (lan != null) {
                log("network: found LAN ${lan.interfaceName} after ${System.currentTimeMillis() - start}ms")
                return lan.interfaceName
            }
            delay(1000)
        }
        return null
    }
}
