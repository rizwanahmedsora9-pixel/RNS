package com.hotspot.billing.core

import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.util.RootShell

/**
 * Phase 6 — Auto Heal System
 * Runs every 10 seconds
 * Checks:
 *  AP alive?
 *  DHCP alive?
 *  DNS alive?
 *  Internet alive?
 *  Clients alive?
 *  Firewall alive?
 *
 * Repair:
 *  Bad: restart everything
 *  Good: DHCP failed -> restart DHCP only
 */
class WatchdogManager {

    data class CheckResult(
        val apAlive: Boolean,
        val dhcpAlive: Boolean,
        val dnsAlive: Boolean,
        val natAlive: Boolean,
        val firewallAlive: Boolean,
        val internetAlive: Boolean,
        val wanIf: String?,
        val lanIf: String?,
        val issues: List<String>
    )

    fun check(lanIf: String?): CheckResult {
        val wanIf = RootShell.defaultRouteInterface()
        val issues = mutableListOf<String>()

        // AP alive?
        val apAlive = if (lanIf != null) {
            val interfaces = RootShell.interfaces()
            val exists = interfaces.any { it.first == lanIf && it.second }
            if (!exists) issues.add("AP interface $lanIf gone (H1)")
            exists
        } else {
            issues.add("No LAN interface set")
            false
        }

        // DHCP alive?
        val dhcpAlive = DhcpManager.isAlive()
        if (!dhcpAlive) issues.add("DHCP not running (H3)")

        // DNS alive?
        val dnsAlive = DnsManager.isAlive()
        if (!dnsAlive) issues.add("DNS not running")

        // NAT alive?
        val natAlive = NatManager.isNatEnabled()
        if (!natAlive) issues.add("NAT chains not first (H6)")

        // Firewall alive?
        val fwAlive = FirewallManager.checkFirewallHealth()
        if (!fwAlive) issues.add("Firewall chains drifted")

        // Internet alive?
        val internetAlive = wanIf != null
        if (!internetAlive) issues.add("No default route (H7)")

        return CheckResult(
            apAlive = apAlive,
            dhcpAlive = dhcpAlive,
            dnsAlive = dnsAlive,
            natAlive = natAlive,
            firewallAlive = fwAlive,
            internetAlive = internetAlive,
            wanIf = wanIf,
            lanIf = lanIf,
            issues = issues
        )
    }

    /**
     * Granular heal: restart only failed component, not whole service.
     * Returns true if healed or no issues, false if needs full restart.
     */
    fun heal(result: CheckResult, log: (String) -> Unit): Boolean {
        if (result.issues.isEmpty()) {
            return true
        }

        log("watchdog: issues ${result.issues.joinToString()} - granular heal")

        var healed = true

        // DHCP failed -> restart DHCP only
        if (!result.dhcpAlive && result.lanIf != null) {
            log("watchdog: DHCP failed, restarting DHCP only")
            val ok = DhcpManager.restart(result.lanIf)
            if (!ok) {
                log("watchdog: DHCP restart failed, needs full restart")
                healed = false
            } else {
                log("watchdog: DHCP healed")
            }
        }

        // DNS failed -> repair DNS only
        if (!result.dnsAlive && result.lanIf != null) {
            log("watchdog: DNS failed, repairing DNS only")
            val ok = DnsManager.repair(result.lanIf)
            if (!ok) healed = false
        }

        // NAT failed -> repair NAT only
        if (!result.natAlive && result.wanIf != null && result.lanIf != null) {
            log("watchdog: NAT failed, repairing NAT only")
            val plan = RootShell.readLanPlan()
            val subnet = plan?.subnet ?: "10.66.0.0/24"
            val ok = NatManager.repair(result.wanIf, result.lanIf, subnet)
            if (!ok) healed = false
        }

        // Firewall failed -> repair firewall only
        if (!result.firewallAlive) {
            log("watchdog: Firewall drifted, repairing")
            val ok = FirewallManager.repair()
            if (!ok) healed = false
        }

        // AP gone -> needs full restart (can't heal AP alone without launcher)
        if (!result.apAlive) {
            log("watchdog: AP gone, needs full restart")
            healed = false
        }

        // Internet gone -> wait, don't restart everything
        if (!result.internetAlive) {
            log("watchdog: internet gone (WAN lost), waiting for recovery")
            // Don't mark as failed, just wait
        }

        if (healed) {
            AppLog.i(AppLog.TAG_WATCHDOG, "watchdog: granular heal success")
        } else {
            AppLog.w(AppLog.TAG_WATCHDOG, "watchdog: granular heal incomplete, full restart needed")
        }

        return healed
    }
}
