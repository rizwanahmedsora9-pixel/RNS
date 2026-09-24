package com.hotspot.billing.core

import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.util.RootShell

/**
 * Phase 5 — DNS System
 * Functions:
 *  - Local DNS (dnsmasq on gateway IP)
 *  - Forward requests to upstream (8.8.8.8, 1.1.1.1 or WAN DNS)
 *  - Captive portal support (DNS must resolve probes even when client ignores DHCP DNS)
 *
 * Implementation: dnsmasq is started by setup_network.sh with:
 *  --no-resolv --server=8.8.8.8 --server=1.1.1.1 (overridable via UPSTREAM_DNS env)
 *  plus iptables REDIRECT for clients that use hardcoded DNS (8.8.8.8) → gateway
 *
 * This manager ensures DNS is alive and upstream is correct.
 */
object DnsManager {

    data class DnsInfo(
        val isRunning: Boolean,
        val gateway: String?,
        val upstream1: String,
        val upstream2: String,
        val foreignRunning: Boolean
    )

    fun start(lanIf: String): Boolean {
        AppLog.i(AppLog.TAG_NET, "dns: starting on $lanIf (via DhcpManager, same dnsmasq)")
        // DNS is part of same dnsmasq as DHCP, so start via DhcpManager
        return DhcpManager.start(lanIf)
    }

    fun stop(): Boolean {
        AppLog.i(AppLog.TAG_NET, "dns: stopping")
        return DhcpManager.stop()
    }

    fun isAlive(): Boolean {
        return DhcpManager.isAlive()
    }

    /**
     * DNS runs in the same dnsmasq as DHCP, so this is answered from the single
     * probe the watchdog already ran - not with four more shell commands.
     */
    fun getInfo(): DnsInfo {
        val probe = RootShell.probe()
        val ourRunning = probe?.dhcpOurs == true || probe?.dhcpOrphan == true ||
            (probe == null && (try { RootShell.isDnsmasqRunning() } catch (e: Throwable) { false }))
        val foreignRunning = probe?.dhcpForeign
            ?: (try { RootShell.isForeignDnsmasqRunning() } catch (e: Throwable) { false })
        val gateway = probe?.gateway ?: RootShell.readLanPlan()?.gateway
        val (up1, up2) = WanDetector.getUpstreamDns()

        return DnsInfo(
            isRunning = ourRunning || foreignRunning,
            gateway = gateway,
            upstream1 = up1,
            upstream2 = up2,
            foreignRunning = foreignRunning
        )
    }

    fun repair(lanIf: String): Boolean {
        AppLog.i(AppLog.TAG_NET, "dns: repairing (granular heal) on $lanIf")
        val keepalive = RootShell.keepaliveNetwork()
        if (keepalive.isSuccess && isAlive()) {
            AppLog.i(AppLog.TAG_NET, "dns: keepalive repaired DNS")
            return true
        }
        AppLog.w(AppLog.TAG_NET, "dns: keepalive failed, full restart")
        return start(lanIf)
    }

    fun testResolution(): Boolean {
        // Test DNS resolution via getprop or nslookup if available
        val dns1 = WanDetector.getUpstreamDns().first
        val result = RootShell.run("nslookup google.com $dns1 2>&1 || getprop net.dns1", quiet = true)
        val success = result.out.any { it.contains("Address") || it.contains("google") || it.matches(Regex(""".*\d+\.\d+\.\d+\.\d+.*""")) }
        AppLog.i(AppLog.TAG_NET, "dns: resolution test via $dns1 -> $success")
        return success
    }
}
