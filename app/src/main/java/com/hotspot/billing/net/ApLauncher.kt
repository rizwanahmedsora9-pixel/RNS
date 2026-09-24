package com.hotspot.billing.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.util.RootShell

/**
 * Decides *how* the customer-facing WiFi network gets created and logs every
 * attempt. Order comes from [ApPlan]: on a mobile uplink (the Hot 8) the system
 * hotspot is first, because that is the radio that actually beacons; on a WiFi
 * uplink it is last so it does not disconnect the internet.
 *
 * Before any attempt the radio is turned on (`svc wifi enable` — setWifiEnabled
 * is a no-op for this targetSdk) and Location services are switched on. Local-only
 * hotspot and WiFi Direct are skipped until the location permission is granted;
 * granting it retries immediately.
 */
class ApLauncher(private val context: Context) {

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

        // Adopt before touching the radio. Enabling WiFi while a hotspot is
        // already up is how a working AP gets torn down.
        adoptExisting(pin, log)?.let { handle ->
            current = handle
            log("ap: adopted an AP that was already up (${handle.interfaceName})")
            return handle
        }

        val wan = try {
            RootShell.defaultRouteInterface()
        } catch (e: Throwable) {
            null
        }
        val wanIsWifi = wan != null && ApPlan.isWifiRadio(wan)
        log(
            "ap: uplink ${wan ?: "none"} is " +
                if (wanIsWifi) "WiFi - system hotspot is tried last so it does not disconnect it"
                else "not WiFi - system hotspot is the method this radio can start"
        )
        // The Hot 8 log: WiFi was off, so P2P returned BUSY and local-only
        // hotspot was refused. Turn the radio on before either is attempted.
        ensureWifiOn(log)
        val locationOk = ensureLocationReady(log)

        val steps = ApPlan.steps(mode, Build.VERSION.SDK_INT, wanIsWifi)
        if (steps.isEmpty()) {
            log("ap: mode is \"${mode.label}\" - not creating anything, waiting for an interface")
            return null
        }
        log("ap: will try ${steps.joinToString(" -> ") { it.label }}")

        for (step in steps) {
            if ((step == ApPlan.Step.LOCAL_ONLY || step == ApPlan.Step.WIFI_DIRECT) && !locationOk) {
                log("ap: skipping ${step.label} - Location permission is not granted yet")
                continue
            }
            log("ap: trying ${step.label} ...")
            val handle = try {
                when (step) {
                    ApPlan.Step.SYSTEM -> trySystemCommand(ssid, pass, log)
                    ApPlan.Step.LOCAL_ONLY -> share.startLocalOnly(log)
                    ApPlan.Step.WIFI_DIRECT -> share.startWifiDirect(ssid, pass, log)
                    ApPlan.Step.ROOT_HOSTAPD -> tryRootHostapd(ssid, pass, log)
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

    /**
     * `WifiManager.setWifiEnabled` is a no-op when the app targets API 29+,
     * which this one does. `svc wifi enable` still works on the Hot 8.
     */
    private fun ensureWifiOn(log: (String) -> Unit) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            log("ap: no WifiManager - cannot tell if WiFi is on")
            return
        }
        if (wifi.isWifiEnabled) {
            log("ap: WiFi is already on")
            return
        }
        log("ap: WiFi is OFF - enabling it (svc wifi enable). P2P returns BUSY and local-only hotspot is refused while it is off")
        try {
            val res = RootShell.run("svc wifi enable")
            val detail = (res.out + res.err).firstOrNull { it.isNotBlank() }?.trim()
            log("ap: svc wifi enable -> exit ${res.code}${detail?.let { " ($it)" } ?: ""}")
        } catch (e: Throwable) {
            log("ap: svc wifi enable threw ${e.javaClass.simpleName}: ${e.message}")
        }
        val deadline = SystemClock.elapsedRealtime() + 8_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (wifi.isWifiEnabled) {
                log("ap: WiFi is on")
                return
            }
            try {
                Thread.sleep(400)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        log("ap: WiFi still off after 8s - AP methods that need the radio will fail")
    }

    /**
     * Permission and location *services* are different switches. The Hot 8 log
     * granted the permission and never turned location mode on, so WiFi Direct
     * stayed disabled (BUSY) even after WiFi came up.
     *
     * @return true when the app may call local-only hotspot / WiFi Direct.
     */
    private fun ensureLocationReady(log: (String) -> Unit): Boolean {
        val mode = try {
            RootShell.run("settings get secure location_mode", quiet = true).out
                .firstOrNull()?.trim()
        } catch (e: Throwable) {
            null
        }
        val servicesOn = mode == "1" || mode == "2" || mode == "3"
        log("ap: location_mode=${mode ?: "unreadable"} (0=off; WiFi Direct stays disabled while it is off)")
        if (!servicesOn) {
            log("ap: switching Location on (settings put secure location_mode 3)")
            try {
                val put = RootShell.run("settings put secure location_mode 3")
                val after = RootShell.run("settings get secure location_mode", quiet = true).out
                    .firstOrNull()?.trim()
                log("ap: location_mode is now ${after ?: "unreadable"} (put exit ${put.code})")
            } catch (e: Throwable) {
                log("ap: could not switch Location on (${e.message})")
            }
            try {
                Thread.sleep(1_500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val granted = ApRadio.hasLocationPermission(context)
        if (!granted) {
            log(
                "ap: Location permission is not granted yet - local-only hotspot throws " +
                    "SecurityException and WiFi Direct is hidden. Grant it and the gateway retries."
            )
        }
        return granted
    }

    /**
     * System hotspot on every Android version. On 9/10 that is
     * `IWifiManager.startSoftAp` as root, not `cmd wifi start-softap` (the Hot 8
     * answers "Unknown command" to that).
     */
    private fun trySystemCommand(ssid: String, pass: String, log: (String) -> Unit): ApHandle? {
        val iface = SoftApController.startAp(ssid, pass, log, context)
        if (iface == null) {
            log("ap[system]: no AP interface stayed up")
            return null
        }
        val fromHostapd = try {
            share.hostapdConfig()
        } catch (e: Throwable) {
            null
        }
        return ApHandle(
            kind = ApKind.SYSTEM_HOTSPOT,
            interfaceName = iface,
            ssid = fromHostapd?.ssid ?: ssid,
            password = fromHostapd?.passphrase ?: pass,
            detail = "system hotspot started from the app",
            leaveAndroidDhcp = true,
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
            detail = "adopted the interface that is already up",
            leaveAndroidDhcp = kind == ApKind.SYSTEM_HOTSPOT &&
                ApPlan.frameworkLikelyOwnsDhcp(iface)
        )
    }

    /** Whether setup_network.sh must leave Android's tether dnsmasq alone. */
    fun shouldLeaveAndroidDhcp(lanIf: String?): Boolean {
        val handle = current
        if (handle?.leaveAndroidDhcp == true) return true
        if (handle != null) return false
        return lanIf != null && ApPlan.frameworkLikelyOwnsDhcp(lanIf)
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
