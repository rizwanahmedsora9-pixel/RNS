package com.hotspot.billing.net

import com.hotspot.billing.util.RootShell

/**
 * Finds the interface the customer-facing AP lives on, and opens the OS
 * hotspot screen as a manual escape hatch.
 *
 * This used to *start* the Android system hotspot too (startSoftAp via
 * app_process / service call / `cmd wifi`). That path is gone - the only AP
 * method is the WiFi Direct group owner, so there is nothing left here but
 * detection. The well-known AP names still cover the legacy `rnsap0`
 * root hostapd an older version may have left behind.
 */
object SoftApController {

    /** Interfaces that mean "this is the hotspot / LAN side", in priority order. */
    private val AP_CANDIDATES = listOf(
        "p2p0", "p2p-wlan0-0", "wifi_p2p0",
        "ap0", "ap1", "swlan0", "wlan1", "wlan2", "softap0", "uap0", "wlan_ap0",
        "rnsap0",
        // USB-OTG ethernet, for the wired phone -> Router2 topology.
        "usb0", "eth0"
    )

    /**
     * Opens the OS hotspot/tethering screen so the user can inspect or flip
     * the system hotspot. Needs root to launch the non-exported activity;
     * falls back to the general wireless settings screen.
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
}
