package com.hotspot.billing.core

import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.net.SoftApController
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
 * client), then ask ONE probe whether there is anything to remove at all - the
 * full cleanup takes 15 s on the Hot 8 and ran on *every* start, which is why
 * the 2026-09-24 14:42 export shows 70 s before the first AP attempt - then
 * remove the kernel rules and drop the AP reservation in parallel when there is
 * genuinely something to drop.
 */
class EmergencyCleaner(
    private val stopPortal: () -> Unit,
    private val releaseAp: () -> Unit,
    private val log: (String) -> Unit
) {

    /**
     * @param reason goes into the log, e.g. "EXIT button", "app start", "service destroyed".
     * @param stopAp false when the gateway is starting up: a hotspot that is
     *   *already beaconing* is exactly what the next step wants to adopt
     *   (System hotspot, a live WiFi Direct group, or the operator's own
     *   toggle), and stopping it - which is what this did on every start -
     *   means rebuilding it from scratch. The things that actually poison the
     *   next session (our dnsmasq, our rules, our shaper) are removed either way.
     * @return true when nothing of ours is left behind.
     */
    suspend fun cleanupEverything(reason: String, lanIf: String? = null, stopAp: Boolean = true): Boolean =
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

            // 2. One probe answers "is there anything of ours to remove?". On a
            //    phone that was stopped cleanly the answer is no, and the whole
            //    15-second teardown is replaced by dropping the stale state files.
            val probe = try {
                RootShell.probe(lanIf, fresh = true)
            } catch (e: Throwable) {
                AppLog.w(AppLog.TAG_SERVICE, "cleanup: probe failed: ${e.message}")
                null
            }
            val hasLeftovers = probe == null || probeNeedsCleanup(probe)

            // Starting a session: never tear down a live AP. Stopping it - which
            // is what this used to do on every start - only means rebuilding what
            // the next step was about to adopt anyway.
            val keepRunningAp = !stopAp
            val runningAp = if (keepRunningAp) {
                try {
                    SoftApController.apDecision(null)?.iface
                } catch (e: Throwable) {
                    null
                }
            } else {
                null
            }

            val parts = coroutineScope {
                val rules = async {
                    if (hasLeftovers) {
                        cleanupRules(lanIf)
                    } else {
                        purgeStateFiles()
                    }
                }
                val ap = async {
                    if (keepRunningAp) {
                        if (runningAp != null) {
                            log(
                                "cleanup: keeping $runningAp - it is beaconing and will be adopted " +
                                    "(Stop, then Start, to recreate the network from scratch)"
                            )
                        } else {
                            log("cleanup: nothing is beaconing - leaving the radio alone")
                        }
                        true
                    } else {
                        try {
                            releaseAp()
                            true
                        } catch (e: Throwable) {
                            log("cleanup: releasing the AP failed: ${e.javaClass.simpleName}: ${e.message}")
                            false
                        }
                    }
                }
                rules.await() to ap.await()
            }

            // 3. Verify - but only when there was something to remove in the
            //    first place. On the clean path a second probe would only repeat
            //    what the first one just said.
            var leftovers = if (hasLeftovers) leftovers(lanIf) else emptyList()
            if (leftovers.isNotEmpty()) {
                log("cleanup: still present after the first pass: ${leftovers.joinToString()}")
                cleanupRules(lanIf)
                leftovers = leftovers(lanIf)
            }

            val took = System.currentTimeMillis() - started
            val clean = leftovers.isEmpty()
            when {
                clean && !hasLeftovers ->
                    log("cleanup: nothing of ours was running - dropped the stale state files (${took}ms)")
                clean ->
                    log("cleanup: system is clean - no dnsmasq, rules, shaper or state of ours left (${took}ms)")
                else ->
                    AppLog.w(
                        AppLog.TAG_SERVICE,
                        "cleanup: ${leftovers.joinToString()} survived two passes (${took}ms) - " +
                            "open the debugger and export the log"
                    )
            }
            if (!parts.first) {
                log("cleanup: the script reported an error while removing rules - see the log above")
            }
            clean
        }

    /** Does the probe show something that only `setup_network.sh cleanup` removes? */
    private fun probeNeedsCleanup(probe: RootShell.Probe): Boolean {
        if (probe.dhcpOurs == true) return true
        if (probe.dhcpOrphan == true) return true
        if (probe.natJump == true) return true
        if (probe.forwardJump == true) return true
        if (probe.inputJump == true) return true
        if (probe.masquerade == true) return true
        if (probe.portalRedirect == true) return true
        if (probe.ruleIif == true) return true
        if (probe.ruleSubnet == true) return true
        if (probe.shaper == true) return true
        if (probe.netshare == true) return true
        return false
    }

    private fun cleanupRules(lanIf: String?): Boolean = try {
        RootShell.cleanupAll(lanIf)
    } catch (e: Throwable) {
        AppLog.e(AppLog.TAG_SERVICE, "cleanup: shell cleanup failed", e)
        false
    }

    /** State files only - one command, ~10 ms. */
    private fun purgeStateFiles(): Boolean = try {
        RootShell.purgeState().isSuccess
    } catch (e: Throwable) {
        AppLog.e(AppLog.TAG_SERVICE, "cleanup: purge-state failed", e)
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
        if (probe.shaper == true) found += "the tc shaper"
        if (probe.netshare == true) found += "our root hostapd"
        return found
    }
}
