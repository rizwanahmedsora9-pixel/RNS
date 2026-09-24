package com.hotspot.billing.core

import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.util.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * EXIT / emergency clean stop.
 *
 * Why this class exists: on 2026-09-24 a debug export showed a dnsmasq from an
 * earlier session (pid 9557) still holding UDP/67 after the app had been closed,
 * so the next start's DHCP server died with
 *
 *     dnsmasq: failed to bind DHCP server socket: Address already in use
 *     dnsmasq: FAILED to start up
 *
 * and Android's own tether dnsmasq could not bind either. Clients then sat on
 * "Obtaining IP address" at a hotspot that looked perfectly healthy.
 *
 * `setup_network.sh stop` cannot fix that by itself: it can only kill what its
 * pidfile points at. The cleaner runs `setup_network.sh cleanup`, which finds
 * every process and every rule of ours BY OWNERSHIP (state paths, chain names,
 * rule priorities), not by a state file that the previous crash may have
 * deleted, and it works for interfaces that no longer exist by that name.
 *
 * Order: stop the portal first (it is the only thing that can still accept a
 * client), then run the two slow, independent parts - removing the kernel rules
 * and dropping the AP reservation / P2P group - at the same time, then verify
 * with a single probe and try exactly once more if something survived.
 */
class EmergencyCleaner(
    private val stopPortal: () -> Unit,
    private val releaseAp: () -> Unit,
    private val log: (String) -> Unit
) {

    /**
     * @param reason goes into the log, e.g. "EXIT button", "app start", "service destroyed".
     * @return true when nothing of ours is left behind.
     */
    suspend fun cleanupEverything(reason: String, lanIf: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            log("cleanup: everything off ($reason)")

            // 1. The portal: drop the listening socket first. While it is up, a
            //    client that reconnects gets a voucher page for a gateway that is
            //    being torn down.
            try {
                stopPortal()
            } catch (e: Throwable) {
                AppLog.w(AppLog.TAG_SERVICE, "cleanup: portal stop failed: ${e.message}")
            }

            // 2. Rules and AP at the same time: `cleanup` is a shell command and
            //    releasing the P2P group / LOHS reservation is a framework call -
            //    they do not depend on each other, and this is the part the user
            //    waits for.
            val rulesOk = coroutineScope {
                val rules = async { cleanupRules(lanIf) }
                val ap = async {
                    try {
                        releaseAp()
                        true
                    } catch (e: Throwable) {
                        log("cleanup: releasing the AP failed: ${e.javaClass.simpleName}: ${e.message}")
                        false
                    }
                }
                rules.await() to ap.await()
            }

            // 3. Verify. A `cleanup` that reported success but left the chain is
            //    exactly the silent failure that made the hotspot "stay visible".
            var leftovers = leftovers(lanIf)
            if (leftovers.isNotEmpty()) {
                log("cleanup: still present after the first pass: ${leftovers.joinToString()}")
                cleanupRules(lanIf)
                leftovers = leftovers(lanIf)
            }

            val took = System.currentTimeMillis() - started
            val clean = leftovers.isEmpty()
            if (clean) {
                log("cleanup: system is clean - no dnsmasq, rules, shaper or state of ours left (${took}ms)")
            } else {
                AppLog.w(
                    AppLog.TAG_SERVICE,
                    "cleanup: ${leftovers.joinToString()} survived two passes (${took}ms) - " +
                        "open the debugger and export the log"
                )
            }
            if (!rulesOk.first) {
                log("cleanup: the script reported an error while removing rules - see the log above")
            }
            clean
        }

    private fun cleanupRules(lanIf: String?): Boolean = try {
        RootShell.cleanupAll(lanIf)
    } catch (e: Throwable) {
        AppLog.e(AppLog.TAG_SERVICE, "cleanup: shell cleanup failed", e)
        false
    }

    /**
     * What of ours is still on the system, from one probe command.
     * An empty list means a clean stop.
     */
    private fun leftovers(lanIf: String?): List<String> {
        // The probe reads the state the cleaner just removed, so it must not be
        // answered from cache.
        val probe = RootShell.probe(lanIf, fresh = true) ?: return emptyList()
        val found = mutableListOf<String>()
        if (probe.dhcpOurs == true) found += "our dnsmasq (pid ${probe.text("dhcp_pid") ?: "?"})"
        if (probe.dhcpOrphan == true) found += "an untracked dnsmasq of ours"
        if (probe.natJump == true) found += "the HS_NAT jump"
        if (probe.forwardJump == true) found += "the HS_FWD jump"
        if (probe.inputJump == true) found += "the HS_IN jump"
        if (probe.masquerade == true) found += "a masquerade rule"
        if (probe.portalRedirect == true) found += "the portal redirect"
        if (probe.ruleIif == true || probe.ruleSubnet == true) found += "policy-routing rules"
        return found
    }
}
