package com.hotspot.billing.core

import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.net.ApHandle
import com.hotspot.billing.net.ApLauncher
import com.hotspot.billing.net.LanPlan
import com.hotspot.billing.util.RootShell

/**
 * The gateway START/STOP state machine.
 *
 * START (6 steps, each reported to the UI as "STARTING… N/6"):
 *  1. Detect the internet source (WAN)
 *  2. Create the LAN (WiFi Direct group owner - the only AP method)
 *  3. Wait for the LAN address (adopt, don't force)
 *  4. Start DHCP (the client MUST get IP + gateway + DNS)
 *  5. Start DNS (same dnsmasq, verified)
 *  6. Enable NAT (+ internet test)
 *
 * STOP: clean shutdown - DHCP/DNS, NAT, then the AP handle.
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

    /** The six steps the UI badge counts ("STARTING… N/6"). */
    val totalSteps: Int = STEP_COUNT

    /**
     * START flow. [onStep] receives 1..6 as each step begins, so the dashboard
     * badge can show progress; the final step (6) covers NAT + the internet
     * test.
     */
    suspend fun start(
        ssid: String,
        log: (String) -> Unit,
        onStep: (Int) -> Unit = {}
    ): StartResult {
        log("network: START requested ssid=$ssid")

        // 1. Detect internet source
        onStep(1)
        log("network: step 1/$STEP_COUNT detecting WAN")
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

        // 2. Create LAN (WiFi Direct group owner - the only method)
        onStep(2)
        log("network: step 2/$STEP_COUNT creating LAN AP (WiFi Direct group owner)")
        var apHandle: ApHandle? = null
        var lanIf: String? = null
        try {
            apHandle = apLauncher.launch(ssid, log)
            lanIf = apHandle?.interfaceName
        } catch (e: Throwable) {
            log("network: AP launcher threw ${e.javaClass.simpleName}: ${e.message}")
            AppLog.e(AppLog.TAG_AP, "network: launcher failed", e)
        }

        if (lanIf == null) {
            val msg = "Could not create the WiFi Direct group (see the debugger for the exact " +
                "reason - Location, P2P state or createGroup). Tap Start to retry."
            log("network: FAILED LAN creation: $msg")
            return StartResult.Failed(msg, Step.LAN_CREATION)
        }
        log("network: LAN = $lanIf")

        // 3. Wait for address (adopt Android's address, don't force)
        onStep(3)
        log("network: step 3/$STEP_COUNT waiting for address on $lanIf")
        val address = apLauncher.waitForAddress(lanIf, 15_000, log)
        if (address == null) {
            AppLog.w(AppLog.TAG_NET, "network: no address on $lanIf after 15s, continuing (may still work)")
        }

        // 4. Start DHCP (must guarantee client gets IP, gateway, DNS)
        onStep(4)
        log("network: step 4/$STEP_COUNT starting DHCP on $lanIf")
        val leaveDhcp = apHandle?.leaveAndroidDhcp == true ||
            apLauncher.shouldLeaveAndroidDhcp(lanIf)
        if (leaveDhcp) {
            log("network: Android owns DHCP on $lanIf - not replacing its dnsmasq")
        }
        val dhcpOk = DhcpManager.start(lanIf, leaveDhcp)
        if (!dhcpOk) {
            val msg = "DHCP failed to start on $lanIf — clients would be stuck at Obtaining IP"
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
        onStep(5)
        log("network: step 5/$STEP_COUNT starting DNS")
        val dnsInfo = DnsManager.getInfo()
        if (!dnsInfo.isRunning) {
            val msg = "DNS not running after DHCP start"
            log("network: FAILED DNS: $msg")
            if (!DnsManager.repair(lanIf)) {
                return StartResult.Failed(msg, Step.DNS_START)
            }
        }
        log("network: DNS running gateway=${dnsInfo.gateway} upstream=${dnsInfo.upstream1},${dnsInfo.upstream2} foreign=${dnsInfo.foreignRunning}")

        // 6. Enable NAT + test internet
        onStep(6)
        log("network: step 6/$STEP_COUNT enabling NAT WAN=${wan.interfaceName} LAN=$lanIf")
        val plan = RootShell.readLanPlan()
        val subnet = plan?.subnet ?: "10.66.0.0/24"
        val natOk = NatManager.enableNat(wan.interfaceName, lanIf, subnet)
        if (!natOk) {
            AppLog.w(AppLog.TAG_NET, "network: NAT enable returned false, but continuing")
        }
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
     * STOP flow — clean shutdown.
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

        // 3. Close AP handle (removes the P2P group)
        apHandle?.let {
            log("network: releasing AP ${it.kind.label} ${it.interfaceName}")
            it.close(log)
        }

        // 4. Stop a root hostapd left by an older version of the app, if any.
        ApLauncher.stopLegacyNetshareAp(log)

        log("network: STOP completed")
    }

    companion object {
        private const val STEP_COUNT = 6
    }
}
