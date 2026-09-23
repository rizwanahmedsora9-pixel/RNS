package com.hotspot.billing.net

import com.hotspot.billing.util.RootShell

/**
 * Brings the Wi-Fi AP up and finds the interface it landed on.
 *
 * Android does not let normal apps touch the real internet-sharing hotspot - it
 * is gated behind signature-level permissions. With root (uid 0) there is a
 * cascade of shell entry points, each available on different Android versions:
 *
 *   cmd wifi start-softap <ssid> wpa2 <pass>   - Android 12+ (AOSP WifiShellCommand)
 *   cmd wifi start-softap                      - some builds, uses the saved config
 *   svc wifi enable-softap                     - seen on some vendor builds
 *   ndc softap startap                         - legacy netd (Android <= 7)
 *
 * On Android 9/10 (e.g. the Infinix Hot 8 this project targets) none of them
 * exists, so the last resort is the user flipping the hotspot toggle in quick
 * settings; [apInterface] detecting the interface a moment later is what makes
 * that "manual" path still automatic - the gateway configures itself the
 * instant the AP comes up.
 */
object SoftApController {

    /** Interfaces that mean "this is the hotspot / LAN side", in priority order. */
    private val AP_CANDIDATES = listOf(
        "ap0", "ap1", "swlan0", "wlan1", "wlan2", "softap0", "uap0", "wlan_ap0",
        // USB-OTG ethernet, for the wired phone -> Router2 topology.
        "usb0", "eth0"
    )

    /** Tries every programmatic start; returns true if an AP interface came up. */
    fun startAp(ssid: String, pass: String, log: (String) -> Unit): Boolean {
        // AOSP syntax: start-softap <ssid> (open|wpa2|...) <passphrase>.
        // WPA2 needs a >= 8 char passphrase, otherwise fall back to an open AP.
        // SSID/pass are the operator's own input, but still strip anything that
        // could break out of the double quotes of the root shell command.
        val safeSsid = shellSafe(ssid.ifBlank { "RNS-Hotspot" })
        val safePass = shellSafe(pass)
        val wpa2 = if (safePass.length >= 8) "wpa2 \"$safePass\"" else "open"
        val attempts = listOf(
            "cmd wifi start-softap \"$safeSsid\" $wpa2",
            "cmd wifi start-softap",
            "svc wifi enable-softap",
            "ndc softap startap"
        )
        for (cmd in attempts) {
            val res = RootShell.run(cmd)
            val detail = (res.out + res.err).firstOrNull { it.isNotBlank() }?.trim() ?: ""
            log("AP start: $cmd -> exit ${res.code}${if (detail.isNotEmpty()) " ($detail)" else ""}")
            if (apInterface(null) != null) return true
            try {
                Thread.sleep(2500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            if (apInterface(null) != null) return true
        }
        return false
    }

    /** Tries to switch the AP back off (best effort, logs each attempt). */
    fun stopAp(log: (String) -> Unit) {
        for (cmd in listOf("cmd wifi stop-softap", "svc wifi disable-softap", "ndc softap stopap")) {
            val res = RootShell.run(cmd)
            log("AP stop: $cmd -> exit ${res.code}")
        }
    }

    /**
     * Opens the OS hotspot/tethering screen so the user can flip the toggle on
     * builds where root cannot start the AP directly. Needs root to launch the
     * non-exported activity; falls back to the general wireless settings screen.
     */
    fun openTetherSettings(log: (String) -> Unit): Boolean {
        val direct = RootShell.run("am start -n com.android.settings/.TetherSettings")
        if (direct.isSuccess) {
            log("opened hotspot settings")
            return true
        }
        val fallback = RootShell.run("am start -a android.settings.WIRELESS_SETTINGS")
        log("opened wireless settings (direct hotspot screen unavailable)")
        return fallback.isSuccess
    }

    /**
     * The interface the hotspot currently lives on, or null if no AP is up.
     *
     * Order: an explicit user pin, then the vendor's declared tethering
     * interface (getprop wifi.tethering.interface - "ap0" on the Hot 8), then a
     * scan for well-known AP names that are UP and are not the WAN/uplink side.
     */
    fun apInterface(pinned: String?): String? {
        val present = RootShell.interfaces()
        val wan = RootShell.defaultRouteInterface()

        pinned?.takeIf { it.isNotEmpty() }?.let { pin ->
            if (present.any { it.first == pin }) return pin
        }

        RootShell.getprop("wifi.tethering.interface")?.let { prop ->
            if (prop != "wlan0" && present.any { it.first == prop }) return prop
        }

        return present
            .filter { it.second }
            .map { it.first }
            .firstOrNull { name -> name in AP_CANDIDATES && name != wan }
    }

    /** Drops every character that could terminate a double-quoted shell string. */
    private fun shellSafe(value: String): String = value.replace(Regex("[\"`$\\\\]"), "")
}
