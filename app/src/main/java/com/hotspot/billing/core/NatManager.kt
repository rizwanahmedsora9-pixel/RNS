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

        // IP forwarding check
        val ipForward = try {
            RootShell.ipForwardEnabled()
        } catch (e: Throwable) {
            null
        }
        if (ipForward == false) {
            AppLog.w(AppLog.TAG_NET, "nat: ip_forward is 0, setup_network.sh should enable it")
        }

        // Ensure policy routing for self-managed AP interfaces
        // This is critical for WiFi Direct / LOHS / root hostapd — without it,
        // forwarded packets hit Android's trailing unreachable rule
        val routeResult = RootShell.ensurePolicyRouting(lanIf, subnet)
        if (!routeResult.isSuccess) {
            AppLog.w(AppLog.TAG_NET, "nat: policy routing ensure failed exit=${routeResult.code}")
        }

        // Verify NAT jump is first in PREROUTING
        val natJumpFirst = try {
            RootShell.jumpIsFirst("nat", "PREROUTING", "HS_NAT")
        } catch (e: Throwable) {
            null
        }
        val fwdJumpFirst = try {
            RootShell.jumpIsFirst("filter", "FORWARD", "HS_FWD")
        } catch (e: Throwable) {
            null
        }

        AppLog.i(AppLog.TAG_NET, "nat: HS_NAT first=$natJumpFirst HS_FWD first=$fwdJumpFirst ip_forward=$ipForward")

        // If jumps not first, keepalive repairs
        if (natJumpFirst == false || fwdJumpFirst == false) {
            AppLog.w(AppLog.TAG_NET, "nat: jumps not first, repairing via keepalive")
            val keepalive = RootShell.keepaliveNetwork()
            if (!keepalive.isSuccess) {
                AppLog.e(AppLog.TAG_NET, "nat: keepalive repair failed")
                return false
            }
        }

        // Final verification: check iptables has masquerade for WAN
        val status = RootShell.networkStatus()
        val hasMasq = status.any { it.contains("MASQUERADE") || it.contains("masquerade") }
        AppLog.i(AppLog.TAG_NET, "nat: masquerade present=$hasMasq")

        return true
    }

    fun disableNat(): Boolean {
        AppLog.i(AppLog.TAG_NET, "nat: disabling")
        val result = RootShell.stopNetwork()
        return result.isSuccess
    }

    fun isNatEnabled(): Boolean {
        val natFirst = try { RootShell.jumpIsFirst("nat", "PREROUTING", "HS_NAT") } catch (e: Throwable) { null }
        val fwdFirst = try { RootShell.jumpIsFirst("filter", "FORWARD", "HS_FWD") } catch (e: Throwable) { null }
        val ipFwd = try { RootShell.ipForwardEnabled() } catch (e: Throwable) { null }

        return (natFirst == true || natFirst == null) && // null = unknown, don't alarm
               (fwdFirst == true || fwdFirst == null) &&
               (ipFwd == true || ipFwd == null)
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
        // Check if default route exists and WAN interface has RX bytes
        val wan = RootShell.defaultRouteInterface()
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
