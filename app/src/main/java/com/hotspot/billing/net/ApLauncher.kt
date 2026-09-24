package com.hotspot.billing.net

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.util.RootShell

/**
 * Brings the customer-facing WiFi network up: a WiFi Direct group owner
 * (the NetShare technique). That is the only method - the earlier cascade of
 * five (system softap, local-only hotspot, WiFi Direct, root hostapd,
 * manual-wait) is gone, so a failure has exactly one place to come from.
 *
 * Before any attempt the radio is turned on (`svc wifi enable` — setWifiEnabled
 * is a no-op for this targetSdk) and Location services are switched on. AP
 * creation is gated on the Location *permission being granted* - checked at
 * the moment of the attempt, not on the fact that a request was dispatched
 * (the Hot 8 log fired an attempt before the grant had confirmed, and the
 * framework answered with a SecurityException).
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
     * Creates the WiFi Direct group. Returns null when it could not be created
     * (permission not confirmed, P2P state not clean, or the framework refused
     * it after a removeGroup + clean-state retry) - the caller surfaces the
     * reason instead of waiting for a manual toggle.
     */
    fun launch(ssid: String?, log: (String) -> Unit): ApHandle? {
        val startedAt = SystemClock.elapsedRealtime()
        log("ap: method = WiFi Direct group owner (the only method)")

        // The Hot 8 log: WiFi was off, so P2P returned BUSY. Turn the radio on
        // before the attempt.
        ensureWifiOn(log)

        // Gate on a CONFIRMED permission grant. A dispatched request is not a
        // grant: attempts fired before the grant confirmed died with
        // SecurityException.
        if (!ApRadio.hasLocationPermission(context)) {
            log(
                "ap: Location permission is not granted yet - createGroup would be hidden or " +
                    "throw SecurityException. Not attempting; the gateway reports it and retries " +
                    "the moment the grant is confirmed."
            )
            return null
        }

        val locationOk = ensureLocationReady(log)
        if (!locationOk) {
            log("ap: Location services could not be switched on - createGroup will answer BUSY")
        }

        val handle = share.startWifiDirect(ssid, JoinConfig.FIXED_PASSPHRASE, log)
        if (handle != null && handle.interfaceName != null) {
            current = handle
            val took = (SystemClock.elapsedRealtime() - startedAt) / 1000
            log("ap: UP in ${took}s - ${handle.joinInstructions()}")
            return handle
        }
        if (handle != null) {
            log("ap: reported success but named no interface - releasing it")
            handle.close(log)
        }
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
        log("ap: WiFi is OFF - enabling it (svc wifi enable). WiFi Direct answers BUSY while it is off")
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
        log("ap: WiFi still off after 8s - createGroup will fail")
    }

    /**
     * Permission and location *services* are different switches. The Hot 8 log
     * granted the permission and never turned location mode on, so WiFi Direct
     * stayed disabled (BUSY) even after WiFi came up.
     *
     * @return true when Location services are on.
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
        return servicesOn
    }

    /** Whether setup_network.sh must leave Android's tether dnsmasq alone. A
     *  WiFi Direct group is not a tethering network, so we always run our own
     *  dnsmasq on it. */
    fun shouldLeaveAndroidDhcp(lanIf: String?): Boolean {
        val handle = current
        if (handle != null) return handle.leaveAndroidDhcp
        return false
    }

    /** Waits until [iface] has an IPv4 address. Configuring the gateway before
     *  the address exists is how we ended up assigning 10.66.0.1 on top of an
     *  address Android was about to write. */
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
        share.releaseWifiDirect(log)
    }

    companion object {
        private const val NETSHARE = "/data/local/tmp/netshare_ap.sh"

        /** Root hostapd from a *previous* version of this app may still run. */
        fun stopLegacyNetshareAp(log: (String) -> Unit) {
            try {
                RootShell.run("sh $NETSHARE stop")
                log("netshare: legacy root hostapd stop requested (no-op when not running)")
            } catch (e: Throwable) {
                // nothing to stop
            }
        }
    }
}
