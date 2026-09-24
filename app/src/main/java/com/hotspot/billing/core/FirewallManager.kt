package com.hotspot.billing.core

import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.util.RootShell

/**
 * Firewall Manager — controls HS_FWD chain
 * - Default DROP for unauthenticated clients
 * - Per-client ACCEPT above DROP for authorized MACs
 * - RETURN for portal bypass
 * - REJECT tcp/443 so clients fall back to HTTP probe
 */
object FirewallManager {

    fun authorize(mac: String, ip: String, currentIp: String? = null): Boolean {
        AppLog.i(AppLog.TAG_NET, "fw: authorize mac=$mac ip=$ip currentIp=$currentIp")
        val result = RootShell.authorizeMac(mac, ip, currentIp)
        if (!result.isSuccess) {
            AppLog.w(AppLog.TAG_NET, "fw: authorize failed exit=${result.code} out=${result.out} err=${result.err}")
            return false
        }
        // Also reserve IP in DHCP so client gets assigned IP on next renew
        DhcpManager.reserve(mac, ip)
        return true
    }

    fun deauthorize(mac: String): Boolean {
        AppLog.i(AppLog.TAG_NET, "fw: deauthorize mac=$mac")
        val result = RootShell.deauthorizeMac(mac)
        if (!result.isSuccess) {
            AppLog.w(AppLog.TAG_NET, "fw: deauthorize failed exit=${result.code}")
            return false
        }
        DhcpManager.unreserve(mac)
        return true
    }

    fun isAuthorized(mac: String): Boolean =
        listAuthorized().any { it.startsWith(mac.lowercase() + " ") || it == mac.lowercase() }

    fun listAuthorized(): List<String> {
        // One read, cached downstream callers share it through the voucher sweep
        // and the watchdog instead of each spawning their own `cat`.
        return try {
            RootShell.run("cat $AUTHORIZED_FILE 2>/dev/null; true", quiet = true).out
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    private const val AUTHORIZED_FILE = "/data/local/tmp/authorized_macs.txt"

    fun ensureChains(): Boolean {
        AppLog.i(AppLog.TAG_NET, "fw: ensuring chains via keepalive")
        val result = RootShell.keepaliveNetwork()
        return result.isSuccess
    }

    fun checkFirewallHealth(): Boolean {
        val probe = RootShell.probe()
        val natFirst = probe?.natJump
            ?: (try { RootShell.jumpIsFirst("nat", "PREROUTING", "HS_NAT") } catch (e: Throwable) { null })
        val fwdFirst = probe?.forwardJump
            ?: (try { RootShell.jumpIsFirst("filter", "FORWARD", "HS_FWD") } catch (e: Throwable) { null })
        val redirect = probe?.portalRedirect

        // A missing portal redirect is as bad as a missing jump: the client has
        // an IP and never sees the sign-in page.
        val healthy = (natFirst == true || natFirst == null) &&
            (fwdFirst == true || fwdFirst == null) &&
            (redirect != false)
        AppLog.i(
            AppLog.TAG_NET,
            "fw: health natFirst=$natFirst fwdFirst=$fwdFirst redirect=$redirect healthy=$healthy"
        )
        return healthy
    }

    fun repair(): Boolean {
        AppLog.i(AppLog.TAG_NET, "fw: repairing (granular heal)")
        return ensureChains()
    }
}
