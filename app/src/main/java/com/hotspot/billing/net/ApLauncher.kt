package com.hotspot.billing.net

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.util.RootShell

/**
 * Decides *how* the customer-facing WiFi network gets created and logs every
 * attempt, in the order this device is most likely to support:
 *
 *  1. an AP interface that is already up (the operator switched the hotspot on);
 *  2. `cmd wifi start-softap` - Android 12+, the real system hotspot;
 *  3. LocalOnlyHotspot - an AP the app may create itself, no toggle needed;
 *  4. WiFi Direct group owner - the NetShare technique, no toggle needed;
 *  5. root hostapd on a second interface (scripts/netshare_ap.sh);
 *  6. give up and wait for a manual toggle, re-arming the moment one appears.
 *
 * 3-5 keep the phone's own WiFi connection alive, which is the whole point: the
 * phone receives internet on wlan0/ccmni and serves customers on the second
 * interface at the same time.
 */
class ApLauncher(private val context: Context) {

    /** One thing we can try, in order. */
    private enum class Step(val label: String) {
        SYSTEM_CMD("system hotspot command"),
        LOCAL_ONLY("local-only hotspot"),
        WIFI_DIRECT("WiFi Direct group (NetShare)"),
        ROOT_HOSTAPD("root hostapd")
    }

    private val share = WifiShareAp(context)

    @Volatile private var current: ApHandle? = null

    fun current(): ApHandle? = current

    fun isApUp(): Boolean {
        val handle = current ?: return false
        if (handle.isClosed()) return false
        val iface = handle.interfaceName ?: return false
        return try {
            RootShell.interfaces().any { it.first == iface && it.second }
        } catch (e: Throwable) {
            AppLog.w(AppLog.TAG_AP, "could not verify the AP interface: ${e.message}")
            false
        }
    }

    /**
     * Brings a network up. Returns null only when nothing worked - the caller
     * then waits for a manual toggle and re-arms automatically.
     */
    fun launch(
        mode: ApMode,
        ssid: String,
        pass: String,
        pin: String?,
        log: (String) -> Unit
    ): ApHandle? {
        val startedAt = SystemClock.elapsedRealtime()
        log("ap: mode = ${mode.label}")

        adoptExisting(pin, log)?.let { handle ->
            current = handle
            log("ap: adopted an AP that was already up (${handle.interfaceName})")
            return handle
        }

        val steps = stepsFor(mode)
        if (steps.isEmpty()) {
            log("ap: mode is \"${mode.label}\" - not creating anything, waiting for an interface")
            return null
        }

        for (step in steps) {
            log("ap: trying ${step.label} ...")
            val handle = try {
                when (step) {
                    Step.SYSTEM_CMD -> trySystemCommand(mode, ssid, pass, log)
                    Step.LOCAL_ONLY -> share.startLocalOnly(log)
                    Step.WIFI_DIRECT -> share.startWifiDirect(ssid, pass, log)
                    Step.ROOT_HOSTAPD -> tryRootHostapd(ssid, pass, log)
                }
            } catch (e: Throwable) {
                log("ap: ${step.label} threw ${e.javaClass.simpleName}: ${e.message}")
                AppLog.e(AppLog.TAG_AP, "${step.label} threw", e)
                null
            }
            if (handle != null && handle.interfaceName != null) {
                current = handle
                val took = (SystemClock.elapsedRealtime() - startedAt) / 1000
                log("ap: UP via ${step.label} in ${took}s - ${handle.joinInstructions()}")
                return handle
            }
            if (handle != null) {
                log("ap: ${step.label} reported success but named no interface - releasing it")
                handle.close(log)
            }
        }

        val took = (SystemClock.elapsedRealtime() - startedAt) / 1000
        log("ap: nothing could create a network in ${took}s - waiting for a hotspot interface " +
            "(the gateway takes over the moment one appears)")
        return null
    }

    private fun stepsFor(mode: ApMode): List<Step> = when (mode) {
        ApMode.SYSTEM -> listOf(Step.SYSTEM_CMD)
        ApMode.LOCAL_ONLY -> listOf(Step.LOCAL_ONLY)
        ApMode.NETSHARE -> listOf(Step.WIFI_DIRECT)
        ApMode.ROOT_AP -> listOf(Step.ROOT_HOSTAPD)
        ApMode.MANUAL -> emptyList()
        ApMode.AUTO ->
            // On Android 12+ the real system hotspot is the best outcome (netd
            // does NAT/DHCP for it), so try it first. On Android 9/10 that shell
            // command does not exist, so go straight to the no-toggle methods.
            if (Build.VERSION.SDK_INT >= 31) {
                listOf(Step.SYSTEM_CMD, Step.LOCAL_ONLY, Step.WIFI_DIRECT, Step.ROOT_HOSTAPD)
            } else {
                listOf(Step.LOCAL_ONLY, Step.WIFI_DIRECT, Step.ROOT_HOSTAPD)
            }
    }

    /** Android 12+: `cmd wifi start-softap <ssid> wpa2 <pass>`. */
    private fun trySystemCommand(mode: ApMode, ssid: String, pass: String, log: (String) -> Unit): ApHandle? {
        if (Build.VERSION.SDK_INT < 31 && mode != ApMode.SYSTEM) {
            log("ap[system]: skipped - this Android (${Build.VERSION.RELEASE}) has no " +
                "`cmd wifi start-softap`; the toggle is the only system path here")
            return null
        }
        val ok = SoftApController.startAp(ssid, pass, log)
        if (!ok) {
            log("ap[system]: no AP interface appeared")
            return null
        }
        val iface = SoftApController.apInterface(null)
        return ApHandle(
            kind = ApKind.SYSTEM_HOTSPOT,
            interfaceName = iface,
            ssid = ssid,
            password = pass,
            detail = "system hotspot started from the app",
            onClose = { SoftApController.stopAp { } }
        )
    }

    /** scripts/netshare_ap.sh: ask the driver for a second interface and run hostapd. */
    private fun tryRootHostapd(ssid: String, pass: String, log: (String) -> Unit): ApHandle? {
        val safePass = ApConfigText.sanitizePassphrase(pass)
        val safeSsid = ssid.replace(Regex("[\"'\\\\]"), "").ifBlank { "RNS-Hotspot" }
        val res = try {
            RootShell.run("sh $NETSHARE start \"$safeSsid\" \"$safePass\"")
        } catch (e: Throwable) {
            log("ap[root]: could not run netshare_ap.sh (${e.message})")
            return null
        }
        res.out.forEach { if (it.isNotBlank()) log("ap[root]: $it") }
        val parsed = ApConfigText.parseKeyValue(res.out.joinToString("\n"))
        val iface = parsed["IFACE"]
        if (res.code != 0 || iface.isNullOrBlank()) {
            val why = (res.err + res.out).firstOrNull { it.contains("ERROR") }?.trim()
                ?: res.err.firstOrNull { it.isNotBlank() }?.trim()
                ?: "exit ${res.code}"
            log("ap[root]: FAILED - $why")
            return null
        }
        log("ap[root]: hostapd is running on $iface as \"${parsed["SSID"]}\"")
        return ApHandle(
            kind = ApKind.ROOT_HOSTAPD,
            interfaceName = iface,
            ssid = parsed["SSID"] ?: safeSsid,
            password = parsed["PASS"] ?: safePass,
            detail = "root hostapd (channel ${parsed["CHANNEL"] ?: "?"}, created=${parsed["CREATED"] ?: "?"})",
            onClose = { RootShell.run("sh $NETSHARE stop") }
        )
    }

    /**
     * Adopts an interface that is already an AP: the operator's own hotspot, a
     * WiFi Direct group we created earlier, or a root hostapd from a previous
     * process (the app was killed but the AP survived).
     */
    fun adoptExisting(pin: String?, log: (String) -> Unit): ApHandle? {
        // A root hostapd from a previous run of this app.
        val netshare = netshareRuntime()
        val netshareIface = netshare["IFACE"]
        if (!netshareIface.isNullOrBlank()) {
            val up = try {
                RootShell.interfaces().any { it.first == netshareIface && it.second }
            } catch (e: Throwable) {
                false
            }
            if (up) {
                log("ap: found a root hostapd still running on $netshareIface")
                return ApHandle(
                    kind = ApKind.ROOT_HOSTAPD,
                    interfaceName = netshareIface,
                    ssid = netshare["SSID"],
                    password = netshare["PASS"],
                    detail = "adopted the root hostapd from a previous run",
                    onClose = { RootShell.run("sh $NETSHARE stop") }
                )
            }
        }

        val iface = try {
            SoftApController.apInterface(pin)
        } catch (e: Throwable) {
            AppLog.w(AppLog.TAG_AP, "interface scan failed: ${e.message}")
            null
        } ?: return null

        val hostapd = try {
            share.hostapdConfig()
        } catch (e: Throwable) {
            null
        }
        val kind = if (share.isWifiDirectActive()) ApKind.WIFI_DIRECT else ApKind.SYSTEM_HOTSPOT
        return ApHandle(
            kind = kind,
            interfaceName = iface,
            ssid = hostapd?.ssid,
            password = hostapd?.passphrase,
            detail = "adopted the interface that is already up"
        )
    }

    fun netshareRuntime(): Map<String, String> = try {
        ApConfigText.parseKeyValue(
            RootShell.run("cat $NETSHARE_RUNTIME 2>/dev/null", quiet = true).out.joinToString("\n")
        )
    } catch (e: Throwable) {
        emptyMap()
    }

    /**
     * Waits until [iface] has an IPv4 address. Configuring the gateway before the
     * address exists is how we ended up assigning 10.66.0.1 on top of an address
     * Android was about to write.
     */
    fun waitForAddress(iface: String, timeoutMs: Long, log: (String) -> Unit): String? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val addresses = try {
                RootShell.lanAddresses(iface)
            } catch (e: Throwable) {
                emptyList()
            }
            if (addresses.isNotEmpty()) {
                log("ap: $iface has ${addresses.joinToString()}")
                return addresses.first()
            }
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        log("ap: $iface still has no IPv4 address after ${timeoutMs / 1000}s - " +
            "the gateway will assign one itself")
        return null
    }

    /** Re-applies Android's routing rules after the LAN interface changed. */
    fun ensureRouting(iface: String, subnet: String, log: (String) -> Unit) {
        try {
            val res = RootShell.ensurePolicyRouting(iface, subnet)
            res.out.forEach { if (it.isNotBlank()) log("route: $it") }
        } catch (e: Throwable) {
            log("route: could not apply policy routing (${e.message})")
        }
    }

    fun release(log: (String) -> Unit) {
        current?.close(log)
        current = null
        share.releaseLocalOnly(log)
        share.releaseWifiDirect(log)
    }

    /** Releases only the framework reservation, keeping the handle reference. */
    fun noteSystemTookTheAp(log: (String) -> Unit) {
        val handle = current ?: return
        log("ap: the system took the network away (${handle.kind.label}) - releasing our handle")
        current = null
        share.releaseLocalOnly { }
    }

    companion object {
        private const val NETSHARE = "/data/local/tmp/netshare_ap.sh"
        private const val NETSHARE_RUNTIME = "/data/local/tmp/netshare.runtime"
    }
}
