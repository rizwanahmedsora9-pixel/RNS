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

    /**
     * One tick, one root command.
     *
     * Everything below is answered by `setup_network.sh probe` (see RootShell.probe):
     * interface, address, ip_forward, our/foreign/orphan dnsmasq, the three jumps,
     * the masquerade, the portal redirect and the policy-routing rules.
     *
     * The previous version ran ~12 separate commands per tick (interfaces, ip
     * rule, iptables -S twice, dnsmasq pid, /proc scan, ip route, ip addr ...).
     * At an 8 s interval that is what saturated the root shell in the 2026-09-24
     * log, and why a health check could "take" 15 s. When the probe cannot run
     * (busy shell, script not deployed) the answer is "unknown" - never a repair
     * that makes it worse.
     */
    fun check(lanIf: String?): CheckResult {
        val probe = RootShell.probe(lanIf)
        val wanIf = probe?.wanIf ?: RootShell.defaultRouteInterface()
        val issues = mutableListOf<String>()

        // AP alive?
        val apAlive: Boolean
        if (probe != null) {
            val reported = probe.lanIf ?: lanIf
            val exists = probe.lanUp ?: (reported != null && interfaceExists(reported))
            apAlive = reported != null && exists
        } else if (lanIf != null) {
            apAlive = interfaceExists(lanIf)
        } else {
            apAlive = false
        }
        if (!apAlive) issues.add("AP interface ${lanIf ?: "?"} gone (H1)")

        // DHCP: ours, Android's, or a leftover of ours that nobody controls.
        val dhcpOurs = probe?.dhcpOurs == true
        val dhcpOrphan = probe?.dhcpOrphan == true
        val dhcpForeign = probe?.dhcpForeign ?: false
        val dhcpAlive = if (probe != null) dhcpOurs || dhcpOrphan || dhcpForeign else DhcpManager.isAlive()
        if (!dhcpAlive) issues.add("DHCP not running (H3)")
        if (dhcpOrphan) issues.add("an untracked dnsmasq of ours is running (H3-orphan)")

        // DNS is served by the same dnsmasq.
        val dnsAlive = dhcpAlive

        // NAT + firewall: jumps, masquerade, portal redirect.
        val natAlive = if (probe != null) {
            probe.natJump == true && probe.masquerade == true && probe.ipForward == true
        } else {
            NatManager.isNatEnabled()
        }
        if (!natAlive) issues.add("NAT/forwarding drifted (H6)")

        val fwAlive = if (probe != null) {
            probe.forwardJump == true && probe.portalRedirect == true
        } else {
            FirewallManager.checkFirewallHealth()
        }
        if (!fwAlive) issues.add("Firewall chains drifted")

        // Policy routing: without it a client on a self-managed AP interface gets
        // an IP and still reaches nothing.
        val policyOk = probe?.let { it.ruleIif == true && it.ruleSubnet == true }
        if (policyOk == false) issues.add("policy routing for $lanIf is gone")

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

    private fun interfaceExists(name: String): Boolean = try {
        RootShell.interfaces().any { it.first == name && it.second }
    } catch (e: Throwable) {
        false
    }

    /**
     * Granular heal: restart only failed component, not whole service.
     * Returns true if healed or no issues, false if needs full restart.
     */
    /**
     * Repairs only what is broken, cheapest thing first.
     *
     * Order matters: repairing forwarding (`setup_network.sh nat`) must never
     * restart DHCP, because that drops every connected client. And a repair that
     * runs while another one is still in flight burns the root shell - which is
     * how a "repair" ended up costing 20 s in the 2026-09-24 log - so keepalive
     * is rate-limited.
     */
    fun heal(result: CheckResult, log: (String) -> Unit): Boolean {
        if (result.issues.isEmpty()) {
            return true
        }

        log("watchdog: issues ${result.issues.joinToString()} - granular heal")

        var healed = true
        val lan = result.lanIf

        // 1. Forwarding / firewall / policy routing: one command, no DHCP touch.
        if ((!result.natAlive || !result.firewallAlive) && lan != null && result.wanIf != null) {
            val subnet = RootShell.readLanPlan()?.subnet ?: "10.66.0.0/24"
            log("watchdog: re-installing forwarding for $lan -> ${result.wanIf} (clients stay connected)")
            val ok = RootShell.repairNat(lan, result.wanIf, subnet)
            if (!ok) {
                log("watchdog: forwarding repair failed, falling back to the full keepalive")
                healed = false
            }
        }

        // 2. DHCP/DNS gone (or an untracked leftover of ours): keepalive starts
        //    ours again and clears the leftover. Rate-limited because it re-reads
        //    iptables and /proc on every run.
        if ((!result.dhcpAlive || !result.dnsAlive) && lan != null) {
            val now = System.currentTimeMillis()
            val lastKeepalive = lastKeepaliveAt.get()
            if (now - lastKeepalive < KEEPALIVE_MIN_INTERVAL_MS) {
                log("watchdog: DHCP missing but a keepalive ran ${now - lastKeepalive}ms ago - waiting")
                healed = false
            } else {
                lastKeepaliveAt.set(now)
                log("watchdog: DHCP/DNS failed, re-asserting them only (keepalive)")
                val ok = DhcpManager.restart(lan)
                if (!ok) {
                    log("watchdog: DHCP could not be restarted - needs a full gateway restart")
                    healed = false
                } else {
                    log("watchdog: DHCP healed")
                }
            }
        }

        // 3. AP gone -> needs the launcher, the caller does the full recovery.
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

    private companion object {
        /** A keepalive re-reads iptables and /proc; do not do it every 8 s tick. */
        const val KEEPALIVE_MIN_INTERVAL_MS = 20_000L

        val lastKeepaliveAt = java.util.concurrent.atomic.AtomicLong(0L)
    }
}
