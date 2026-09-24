package com.hotspot.billing.core

import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.util.RootShell

/**
 * Phase 4 — NAT Routing
 * Functions:
 *  Enable:
 *   - IP forwarding
 *   - iptables masquerade
 *   - forward rules
 *   - policy routing (ip rule iif <lan> lookup main)
 *
 * Flow:
 *  Internet
 *    |
 *  Phone WAN (ccmni0 / wlan0)
 *    |
 *   NAT
 *    |
 *  Client WiFi (ap0 / p2p0)
 */
object NatManager {

    fun enableNat(wanIf: String, lanIf: String, subnet: String = "10.66.0.0/24"): Boolean {
        AppLog.i(AppLog.TAG_NET, "nat: enabling WAN=$wanIf LAN=$lanIf subnet=$subnet")

        // One command that installs the forwarding pieces for THIS uplink
        // (ip_forward, jumps, portal/DNS redirects, masquerade, policy routing).
        //
        // It replaces what the old path did - a jumpIsFirst check, a keepalive
        // (the 20 s command from the debug log) and a "status" parse - and it
        // never restarts DHCP, so enabling NAT cannot disconnect a client.
        val installed = RootShell.repairNat(lanIf, wanIf, subnet)
        if (!installed) {
            AppLog.w(AppLog.TAG_NET, "nat: could not install forwarding rules for $lanIf -> $wanIf")
        }

        val probe = RootShell.probe(lanIf, fresh = true)
        val ipForward = probe?.ipForward ?: try { RootShell.ipForwardEnabled() } catch (e: Throwable) { null }
        val natJump = probe?.natJump
        val fwdJump = probe?.forwardJump
        val masq = probe?.masquerade
        AppLog.i(
            AppLog.TAG_NET,
            "nat: HS_NAT first=$natJump HS_FWD first=$fwdJump ip_forward=$ipForward masq=$masq"
        )
        if (masq == false) {
            AppLog.w(
                AppLog.TAG_NET,
                "nat: no MASQUERADE for $subnet on $wanIf - forwarded client packets " +
                    "would leave with a private source address and be dropped"
            )
        }
        return installed
    }

    fun disableNat(): Boolean {
        AppLog.i(AppLog.TAG_NET, "nat: disabling")
        val result = RootShell.stopNetwork()
        return result.isSuccess
    }

    fun isNatEnabled(): Boolean {
        val probe = RootShell.probe()
        val natFirst = probe?.natJump
            ?: (try { RootShell.jumpIsFirst("nat", "PREROUTING", "HS_NAT") } catch (e: Throwable) { null })
        val fwdFirst = probe?.forwardJump
            ?: (try { RootShell.jumpIsFirst("filter", "FORWARD", "HS_FWD") } catch (e: Throwable) { null })
        val ipFwd = probe?.ipForward
            ?: (try { RootShell.ipForwardEnabled() } catch (e: Throwable) { null })
        val masq = probe?.masquerade

        return (natFirst == true || natFirst == null) && // null = unknown, don't alarm
               (fwdFirst == true || fwdFirst == null) &&
               (masq != false) &&
               (ipFwd == true || ipFwd == null)
    }

    /**
     * Everything a client needs to reach the internet, as one answer. Used by
     * the watchdog to decide between "repair forwarding" and "nothing to do".
     */
    fun forwardingHealthy(): Boolean? {
        val probe = RootShell.probe() ?: return null
        if (probe.wanIf == null) return null
        return probe.natJump == true && probe.forwardJump == true &&
            probe.masquerade == true && probe.ipForward == true &&
            probe.ruleIif == true && probe.ruleSubnet == true
    }

    fun repair(wanIf: String, lanIf: String, subnet: String): Boolean {
        AppLog.i(AppLog.TAG_NET, "nat: repairing (granular heal) WAN=$wanIf LAN=$lanIf")
        val keepalive = RootShell.keepaliveNetwork()
        if (!keepalive.isSuccess) {
            AppLog.w(AppLog.TAG_NET, "nat: keepalive failed, trying full enable")
            return enableNat(wanIf, lanIf, subnet)
        }
        return true
    }

    fun checkInternet(): Boolean {
        // Check if default route exists and WAN interface has RX bytes.
        // The probe already resolved the uplink, so this costs no extra command.
        val wan = RootShell.probe()?.wanIf ?: RootShell.defaultRouteInterface()
        if (wan == null) {
            AppLog.w(AppLog.TAG_NET, "nat: no default route, no internet")
            return false
        }
        // Try ping via shell (if available) or just check route
        val ping = RootShell.run("ping -c 1 -W 2 8.8.8.8 2>&1", quiet = true)
        val hasInternet = ping.isSuccess || ping.out.any { it.contains("1 received") || it.contains("bytes from") }
        AppLog.i(AppLog.TAG_NET, "nat: internet check via ping 8.8.8.8 -> $hasInternet")
        return hasInternet
    }
}
