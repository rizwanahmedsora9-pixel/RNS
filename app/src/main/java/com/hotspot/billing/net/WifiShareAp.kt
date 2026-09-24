package com.hotspot.billing.net

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.util.RootShell
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Creates a customer-facing WiFi network **without** the Android hotspot toggle.
 *
 * Two framework mechanisms can do that while the phone stays connected to its own
 * WiFi (so it keeps receiving internet and can send it out at the same time):
 *
 *  1. **LocalOnlyHotspot** (API 26+) - an AP the app may start on its own. Android
 *     deliberately gives it no internet; with root we add the NAT, DHCP and portal
 *     ourselves, which turns it into a normal hotspot.
 *  2. **WiFi Direct group owner** (the technique NetShare uses) - the phone becomes
 *     a P2P Group Owner, which is a real AP legacy clients can join. Android does
 *     not route it either; again root supplies the routing.
 *
 * Both need Location switched *on*: Android refuses to create any WiFi network
 * while location services are off, and reports it as a generic failure. Every
 * attempt, callback and error code here is written to [AppLog] so the reason is
 * never a mystery.
 *
 * Every method must be called from a background thread - it waits on framework
 * callbacks with a latch, which would ANR on the main thread.
 */
class WifiShareAp(private val context: Context) {

    private val app = context.applicationContext

    @Volatile private var lohsReservation: Any? = null
    @Volatile private var p2pManager: WifiP2pManager? = null
    @Volatile private var p2pChannel: WifiP2pManager.Channel? = null
    @Volatile private var p2pGroupActive = false

    // ------------------------------------------------------------------ LocalOnlyHotspot

    fun startLocalOnly(log: (String) -> Unit): ApHandle? {
        if (Build.VERSION.SDK_INT < 26) {
            log("ap[lohs]: needs Android 8.0+, this device is API ${Build.VERSION.SDK_INT}")
            return null
        }
        if (isMainThread()) {
            log("ap[lohs]: BUG - called on the main thread, refusing (would freeze the UI)")
            return null
        }
        if (!ApRadio.hasLocationPermission(app)) {
            log(
                "ap[lohs]: skipped - Location permission is not granted. " +
                    "startLocalOnlyHotspot throws SecurityException (Coarse Location) instead of a callback."
            )
            return null
        }
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            log("ap[lohs]: no WifiManager on this device")
            return null
        }
        log("ap[lohs]: wifi enabled=${wifi.isWifiEnabled} - requesting a local-only hotspot")
        if (!wifi.isWifiEnabled) {
            log("ap[lohs]: WiFi is OFF - not calling startLocalOnlyHotspot (the driver refuses, and the error is generic)")
            return null
        }

        val before = interfaceNames()
        val latch = CountDownLatch(1)
        var failure = -1
        var reservation: WifiManager.LocalOnlyHotspotReservation? = null
        val handler = Handler(Looper.getMainLooper())

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                    override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation?) {
                        reservation = res
                        AppLog.i(AppLog.TAG_AP, "lohs: onStarted (reservation=$res)")
                        latch.countDown()
                    }

                    override fun onFailed(reason: Int) {
                        failure = reason
                        AppLog.w(AppLog.TAG_AP, "lohs: onFailed reason=$reason (${lohsFailure(reason)})")
                        latch.countDown()
                    }

                    override fun onStopped() {
                        lohsReservation = null
                        AppLog.w(
                            AppLog.TAG_AP,
                            "lohs: onStopped - the system took the AP away (mode change / user action)"
                        )
                    }
                }, handler)
            }
        } catch (e: Throwable) {
            log("ap[lohs]: startLocalOnlyHotspot threw ${e.javaClass.simpleName}: ${e.message}")
            AppLog.e(AppLog.TAG_AP, "lohs: request failed", e)
            return null
        }

        if (!latch.await(CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            log("ap[lohs]: no callback within ${CALLBACK_TIMEOUT_MS / 1000}s - giving up on this method")
            return null
        }
        if (failure != -1) {
            log("ap[lohs]: FAILED - ${lohsFailure(failure)}")
            hintForLohsFailure(failure, log)
            return null
        }
        // Local val, not the captured var: the framework writes the var from the
        // callback thread, so the compiler will not smart-cast it.
        val granted = reservation
        if (granted == null) {
            log("ap[lohs]: callback said started but handed back no reservation")
            return null
        }
        lohsReservation = granted

        val config = lohsCredentials(granted, log)
        val wan = try {
            RootShell.defaultRouteInterface()
        } catch (e: Throwable) {
            null
        }
        val iface = discoverInterface(before, wan, INTERFACE_TIMEOUT_MS, log)
        if (iface == null) {
            log("ap[lohs]: hotspot started but no interface appeared within " +
                "${INTERFACE_TIMEOUT_MS / 1000}s - releasing it")
            releaseLocalOnly(log)
            return null
        }
        log("ap[lohs]: UP on $iface - ${config.ssid ?: "SSID unknown"} / ${config.passphrase ?: "?"}")
        return ApHandle(
            kind = ApKind.LOCAL_ONLY,
            interfaceName = iface,
            ssid = config.ssid,
            password = config.passphrase,
            detail = "local-only hotspot on $iface" +
                (config.channel?.let { ", channel $it" } ?: ""),
            onClose = { releaseLocalOnly { } }
        )
    }

    fun releaseLocalOnly(log: (String) -> Unit) {
        val reservation = lohsReservation ?: return
        lohsReservation = null
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                (reservation as WifiManager.LocalOnlyHotspotReservation).close()
                log("ap[lohs]: reservation closed")
            }
        } catch (e: Throwable) {
            log("ap[lohs]: closing the reservation threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * The SSID/password of a local-only hotspot.
     *
     * Android 11+ exposes [android.net.wifi.SoftApConfiguration]; on Android 8-10
     * `getWifiConfiguration()` (public since API 26, deprecated in 30) carries them.
     * If the framework hands back nothing, the hostapd config the system itself
     * wrote is read with root - the operator always gets told what to join.
     */
    @Suppress("DEPRECATION")
    private fun lohsCredentials(reservation: Any, log: (String) -> Unit): ApConfigText.ApConfig {
        val typed = reservation as WifiManager.LocalOnlyHotspotReservation

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val softAp = typed.softApConfiguration
                val ssid = softAp.ssid
                val pass = softAp.passphrase
                if (!ssid.isNullOrBlank()) {
                    log("ap[lohs]: credentials from SoftApConfiguration")
                    return ApConfigText.ApConfig(ssid = unquote(ssid), passphrase = pass)
                }
            } catch (e: Throwable) {
                log("ap[lohs]: getSoftApConfiguration threw ${e.javaClass.simpleName}: ${e.message}")
            }
        }

        try {
            val wifiConfig = typed.wifiConfiguration
            val ssid = wifiConfig?.SSID
            val pass = wifiConfig?.preSharedKey
            if (!ssid.isNullOrBlank()) {
                log("ap[lohs]: credentials from WifiConfiguration")
                return ApConfigText.ApConfig(ssid = unquote(ssid), passphrase = pass)
            }
            log("ap[lohs]: getWifiConfiguration() returned nothing usable")
        } catch (e: Throwable) {
            log("ap[lohs]: getWifiConfiguration() threw ${e.javaClass.simpleName}: ${e.message}")
        }

        val fromHostapd = hostapdConfig()
        if (fromHostapd != null && fromHostapd.ssid != null) {
            log("ap[lohs]: credentials read from the system hostapd.conf (root)")
            return fromHostapd
        }
        log("ap[lohs]: could not read the SSID/password - open the debugger's full report to see hostapd.conf")
        return ApConfigText.ApConfig()
    }

    /** WifiConfiguration.SSID is stored quoted (`"AndroidShare_3012"`). */
    private fun unquote(value: String): String = value.trim().removeSurrounding("\"")

    // ------------------------------------------------------------------ WiFi Direct (NetShare)

    fun startWifiDirect(ssid: String?, pass: String?, log: (String) -> Unit): ApHandle? =
        startWifiDirect(ssid, pass, log, attempt = 0)

    private fun startWifiDirect(
        ssid: String?,
        pass: String?,
        log: (String) -> Unit,
        attempt: Int
    ): ApHandle? {
        if (isMainThread()) {
            log("ap[p2p]: BUG - called on the main thread, refusing (would freeze the UI)")
            return null
        }
        if (!ApRadio.hasLocationPermission(app)) {
            log("ap[p2p]: skipped - Location permission is not granted")
            return null
        }
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi != null && !wifi.isWifiEnabled) {
            log(
                "ap[p2p]: skipped - WiFi is off. " +
                    ApPlan.p2pBusyHint(wifiEnabled = false, locationPermission = true, locationServicesOn = true)
            )
            return null
        }
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
            log("ap[p2p]: this device does not advertise android.hardware.wifi.direct - trying anyway")
        }
        // A channel opened while WiFi Direct was disabled keeps answering BUSY
        // after the radio comes up. Always open a fresh one.
        p2pChannel = null
        if (!waitForP2pEnabled(log)) {
            log("ap[p2p]: WiFi Direct did not report enabled - trying createGroup anyway")
        }
        val manager = app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        if (manager == null) {
            log("ap[p2p]: no WifiP2pManager on this device")
            return null
        }
        p2pManager = manager

        if (p2pChannel == null) {
            p2pChannel = manager.initialize(app, Looper.getMainLooper()) {
                p2pGroupActive = false
                AppLog.w(AppLog.TAG_AP, "p2p: channel disconnected - the WiFi Direct group is gone")
            }
        }
        val channel = p2pChannel
        if (channel == null) {
            log("ap[p2p]: could not open a WiFi Direct channel")
            return null
        }

        val before = interfaceNames()
        val wantedSsid = ApConfigText.normalizeDirectSsid(ssid)
        val wantedPass = ApConfigText.sanitizePassphrase(pass)
        val latch = CountDownLatch(1)
        var failure = -1

        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.i(AppLog.TAG_AP, "p2p: createGroup accepted")
                latch.countDown()
            }

            override fun onFailure(reason: Int) {
                failure = reason
                AppLog.w(AppLog.TAG_AP, "p2p: createGroup refused, reason=$reason (${p2pFailure(reason)})")
                latch.countDown()
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val config = WifiP2pConfig.Builder()
                    .setNetworkName(wantedSsid)
                    .setPassphrase(wantedPass)
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                    .enablePersistentMode(false)
                    .build()
                log("ap[p2p]: creating WiFi Direct group \"$wantedSsid\" (own name/password, 2.4GHz)")
                manager.createGroup(channel, config, listener)
            } else {
                log("ap[p2p]: creating a WiFi Direct group (Android picks the DIRECT-xx name and password)")
                manager.createGroup(channel, listener)
            }
        } catch (e: Throwable) {
            log("ap[p2p]: createGroup threw ${e.javaClass.simpleName}: ${e.message}")
            AppLog.e(AppLog.TAG_AP, "p2p: createGroup threw", e)
            return null
        }

        if (!latch.await(CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            log("ap[p2p]: no callback within ${CALLBACK_TIMEOUT_MS / 1000}s - giving up on this method")
            return null
        }
        if (failure != -1) {
            log("ap[p2p]: FAILED - ${p2pFailure(failure)}")
            hintForP2pFailure(failure, log)
            if (failure == WifiP2pManager.BUSY) {
                val existing = requestGroup(channel, log)
                if (existing != null && existing.isGroupOwner) {
                    log("ap[p2p]: BUSY because a group already exists - adopting it")
                    return handleFromGroup(existing, before, wantedSsid, wantedPass, log)
                }
                if (attempt == 0) {
                    log("ap[p2p]: no existing group - dropping the channel and retrying once")
                    p2pChannel = null
                    try {
                        Thread.sleep(1_500)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return null
                    }
                    return startWifiDirect(ssid, pass, log, attempt = 1)
                }
                log("ap[p2p]: still BUSY with no group. WiFi Direct is disabled, not held by another app.")
            }
            return null
        }

        val group = requestGroup(channel, log)
        val realSsid = group?.networkName ?: wantedSsid
        val realPass = group?.passphrase ?: wantedPass
        log("ap[p2p]: group formed - \"$realSsid\" / $realPass (group owner=${group?.isGroupOwner})")

        val wan = try {
            RootShell.defaultRouteInterface()
        } catch (e: Throwable) {
            null
        }
        var iface = hiddenInterfaceName(group)
        if (iface == null) {
            iface = discoverInterface(before, wan, INTERFACE_TIMEOUT_MS, log)
        } else {
            log("ap[p2p]: group reports interface $iface")
        }
        if (iface == null) {
            log("ap[p2p]: group formed but no interface appeared within " +
                "${INTERFACE_TIMEOUT_MS / 1000}s - removing the group")
            releaseWifiDirect(log)
            return null
        }
        p2pGroupActive = true

        return ApHandle(
            kind = ApKind.WIFI_DIRECT,
            interfaceName = iface,
            ssid = realSsid,
            password = realPass,
            detail = "WiFi Direct group on $iface (NetShare-style)",
            onClose = { releaseWifiDirect { } }
        )
    }

    private fun handleFromGroup(
        group: android.net.wifi.p2p.WifiP2pGroup,
        before: List<String>,
        wantedSsid: String,
        wantedPass: String,
        log: (String) -> Unit
    ): ApHandle? {
        val realSsid = group.networkName ?: wantedSsid
        val realPass = group.passphrase ?: wantedPass
        val wan = try { RootShell.defaultRouteInterface() } catch (e: Throwable) { null }
        var iface = hiddenInterfaceName(group)
        if (iface == null) iface = discoverInterface(before, wan, INTERFACE_TIMEOUT_MS, log)
        else log("ap[p2p]: group reports interface $iface")
        if (iface == null) {
            log("ap[p2p]: group exists but no interface appeared")
            return null
        }
        p2pGroupActive = true
        return ApHandle(
            kind = ApKind.WIFI_DIRECT,
            interfaceName = iface,
            ssid = realSsid,
            password = realPass,
            detail = "WiFi Direct group on $iface (adopted)",
            onClose = { releaseWifiDirect { } }
        )
    }

    /**
     * WIFI_P2P_STATE_CHANGED_ACTION is sticky, so the current state arrives
     * as soon as we register. createGroup returns BUSY while this is disabled.
     */
    private fun waitForP2pEnabled(log: (String) -> Unit): Boolean {
        val filter = IntentFilter(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        val latch = java.util.concurrent.CountDownLatch(1)
        var enabled = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                    enabled = true
                    latch.countDown()
                }
            }
        }
        val sticky = try {
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
        } catch (e: Throwable) {
            log("ap[p2p]: could not listen for WiFi Direct state (${e.message})")
            return false
        }
        val stickyState = sticky?.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ?: -1
        if (stickyState == WifiP2pManager.WIFI_P2P_STATE_ENABLED) enabled = true
        if (!enabled) {
            try {
                latch.await(6, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        try {
            app.unregisterReceiver(receiver)
        } catch (e: Throwable) {
            // already unregistered
        }
        log(if (enabled) "ap[p2p]: WiFi Direct is enabled" else "ap[p2p]: WiFi Direct is still disabled")
        return enabled
    }

    fun releaseWifiDirect(log: (String) -> Unit) {
        val manager = p2pManager
        val channel = p2pChannel
        p2pGroupActive = false
        if (manager == null || channel == null) return
        try {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = AppLog.i(AppLog.TAG_AP, "p2p: group removed")
                override fun onFailure(reason: Int) =
                    AppLog.w(AppLog.TAG_AP, "p2p: removeGroup failed reason=$reason (${p2pFailure(reason)})")
            })
            log("ap[p2p]: removeGroup requested")
        } catch (e: Throwable) {
            log("ap[p2p]: removeGroup threw ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    fun isWifiDirectActive(): Boolean = p2pGroupActive

    private fun requestGroup(channel: WifiP2pManager.Channel, log: (String) -> Unit): WifiP2pGroup? {
        val latch = CountDownLatch(1)
        var result: WifiP2pGroup? = null
        try {
            p2pManager?.requestGroupInfo(channel, WifiP2pManager.GroupInfoListener { group ->
                result = group
                latch.countDown()
            })
        } catch (e: Throwable) {
            log("ap[p2p]: requestGroupInfo threw ${e.javaClass.simpleName}: ${e.message}")
            return null
        }
        if (!latch.await(GROUP_INFO_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            log("ap[p2p]: requestGroupInfo never answered")
            return null
        }
        if (result == null) log("ap[p2p]: requestGroupInfo returned null (group not visible yet)")
        return result
    }

    /** `WifiP2pGroup.getInterface()` is @hide; worth one careful attempt. */
    private fun hiddenInterfaceName(group: WifiP2pGroup?): String? {
        if (group == null) return null
        return try {
            val method = group.javaClass.getMethod("getInterface")
            method.invoke(group) as? String
        } catch (e: Throwable) {
            AppLog.d(AppLog.TAG_AP, "p2p: getInterface() is hidden on this build (${e.javaClass.simpleName})")
            null
        }
    }

    // ------------------------------------------------------------------ shared helpers

    /** The AP the phone's own radio is running, as hostapd wrote it (needs root). */
    fun hostapdConfig(): ApConfigText.ApConfig? = try {
        val text = RootShell.run(
            "cat /data/vendor/wifi/hostapd/hostapd.conf /data/misc/wifi/hostapd.conf 2>/dev/null",
            quiet = true
        ).out.joinToString("\n")
        ApConfigText.parseHostapd(text)
    } catch (e: Throwable) {
        null
    }

    private fun interfaceNames(): Set<String> = try {
        RootShell.interfaces().map { it.first }.toSet()
    } catch (e: Throwable) {
        AppLog.w(AppLog.TAG_AP, "could not list interfaces: ${e.message}")
        emptySet()
    }

    private fun discoverInterface(
        before: Set<String>,
        wan: String?,
        timeoutMs: Long,
        log: (String) -> Unit
    ): String? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var lastSeen: List<String> = emptyList()
        while (SystemClock.elapsedRealtime() < deadline) {
            val now = try {
                RootShell.interfaces()
            } catch (e: Throwable) {
                emptyList()
            }
            lastSeen = now.map { it.first }
            val pick = ApConfigText.pickApInterface(before, now, wan)
            if (pick != null) {
                log("ap: interface $pick appeared (before=${before.joinToString()}, now=${lastSeen.joinToString()})")
                return pick
            }
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        log("ap: no new interface after ${timeoutMs / 1000}s (interfaces: ${lastSeen.joinToString()})")
        return null
    }

    private fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    companion object {
        private const val CALLBACK_TIMEOUT_MS = 30_000L
        private const val GROUP_INFO_TIMEOUT_MS = 10_000L
        private const val INTERFACE_TIMEOUT_MS = 20_000L

        /** WifiManager.LocalOnlyHotspotCallback error codes, in plain words. */
        fun lohsFailure(reason: Int): String = when (reason) {
            1 -> "ERROR_NO_CHANNEL - no WiFi channel available (try 2.4GHz only, or move off the current channel)"
            2 -> "ERROR_GENERIC - the WiFi stack refused"
            3 -> "ERROR_INCOMPATIBLE_MODE - WiFi is off, or this radio cannot run an AP and a WiFi connection at the same time"
            4 -> "ERROR_TETHERING_DISALLOWED - a device policy/carrier setting forbids sharing"
            else -> "unknown reason $reason"
        }

        /** WifiP2pManager.ActionListener error codes, in plain words. */
        fun p2pFailure(reason: Int): String = when (reason) {
            0 -> "ERROR - the framework refused (often: Location permission or Location services are off)"
            1 -> "P2P_UNSUPPORTED - this device has no WiFi Direct"
            2 -> "BUSY - WiFi Direct is disabled or not accepting groups (WiFi off, Location off, or a stale channel). Not necessarily another group."
            3 -> "NO_SERVICE_REQUESTS - service discovery was not registered"
            4 -> "NETWORK_ALREADY_CONNECTED - already in a WiFi Direct group"
            else -> "unknown reason $reason"
        }

        private fun hintForLohsFailure(reason: Int, log: (String) -> Unit) {
            when (reason) {
                3 -> log("ap[lohs]: hint - switch WiFi ON and Location ON, then try again; " +
                    "if the radio cannot do STA+AP, only the Android hotspot toggle will work")
                4 -> log("ap[lohs]: hint - sharing is blocked by a device/carrier policy")
                1 -> log("ap[lohs]: hint - the current WiFi channel cannot host an AP; " +
                    "reconnect the phone to a 2.4GHz network (channel 1-11)")
                else -> log("ap[lohs]: hint - check Location permission and Location services")
            }
        }

        private fun hintForP2pFailure(reason: Int, log: (String) -> Unit) {
            when (reason) {
                0 -> log("ap[p2p]: hint - grant Location permission and switch Location ON; " +
                    "Android hides WiFi APIs from apps without it")
                1 -> log("ap[p2p]: hint - this phone has no WiFi Direct; use the local-only hotspot or the Android toggle")
                2 -> log("ap[p2p]: hint - " + ApPlan.p2pBusyHint(
                    wifiEnabled = true,
                    locationPermission = true,
                    locationServicesOn = true
                ))
                4 -> log("ap[p2p]: hint - a WiFi Direct group already exists; it is removed and retried")
                else -> Unit
            }
        }
    }
}
