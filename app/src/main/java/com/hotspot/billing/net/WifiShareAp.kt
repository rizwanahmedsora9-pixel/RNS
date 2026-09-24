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
import android.os.Looper
import android.os.SystemClock
import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.util.RootShell
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Creates the customer-facing WiFi network **without** the Android hotspot
 * toggle: the phone becomes a WiFi Direct **group owner** (the technique
 * NetShare uses), which is a real AP legacy clients can join. Android does
 * not route the group either; root supplies the routing.
 *
 * This used to be one of five fallback methods (system softap, local-only
 * hotspot, WiFi Direct, root hostapd, manual wait). It is now the *only*
 * method - a single reproducible path, with every attempt, callback and error
 * code written to [AppLog] so the reason a start failed is never a mystery.
 *
 * WiFi Direct needs Location switched *on*: Android refuses to create any WiFi
 * network while location services are off, and reports it as a generic failure
 * (createGroup BUSY).
 *
 * Every method must be called from a background thread - it waits on framework
 * callbacks with a latch, which would ANR on the main thread.
 */
class WifiShareAp(private val context: Context) {

    private val app = context.applicationContext

    @Volatile private var p2pManager: WifiP2pManager? = null
    @Volatile private var p2pChannel: WifiP2pManager.Channel? = null
    @Volatile private var p2pGroupActive = false

    // ------------------------------------------------------------------ WiFi Direct (NetShare)

    fun startWifiDirect(ssid: String?, pass: String?, log: (String) -> Unit): ApHandle? {
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
                "ap[p2p]: skipped - WiFi is off. createGroup returns BUSY (reason 2) " +
                    "while the radio is off; that is the disabled state, not another group."
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

        // The Hot 8 log: createGroup answered reason=2 (BUSY) on *every* attempt,
        // even after a full HAL re-init - a leftover P2P group, not a transient
        // conflict. So every createGroup attempt starts from a confirmed clean
        // P2P state, with removeGroup in between.
        if (!ensureCleanP2pState(channel, log)) {
            log("ap[p2p]: P2P state is not clean - createGroup would be refused; tap Start to retry")
            return null
        }

        val before = interfaceNames()
        val wantedSsid = ApConfigText.normalizeDirectSsid(ssid)
        val wantedPass = ApConfigText.sanitizePassphrase(pass)
        var created = tryCreateGroup(manager, channel, wantedSsid, wantedPass, log)

        if (created == null) {
            // Whatever BUSY / NETWORK_ALREADY_CONNECTED meant, a group is in the
            // way: remove it, confirm the state is clean, and attempt once more.
            log("ap[p2p]: first createGroup failed - removing any group and retrying once")
            try {
                Thread.sleep(1_000)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
            val fresh = freshChannel(manager, log)
            if (fresh != null && ensureCleanP2pState(fresh, log)) {
                created = tryCreateGroup(manager, fresh, wantedSsid, wantedPass, log)
            }
        }

        if (created == null) {
            log("ap[p2p]: createGroup still refused after removeGroup + clean-state retry")
            return null
        }

        val handle = created.handle
        val realSsid = handle.ssid
        val realPass = handle.password
        log("ap[p2p]: group formed - \"$realSsid\" / $realPass (group owner confirmed)")

        val wan = try {
            RootShell.defaultRouteInterface()
        } catch (e: Throwable) {
            null
        }
        var iface = hiddenInterfaceName(created.group)
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

    /** The outcome of one successful createGroup: the handle plus the live group. */
    private class CreatedGroup(val handle: ApHandle, val group: WifiP2pGroup?)

    /**
     * One createGroup attempt. Returns a handle carrying the REAL ssid/password
     * (read back from the group, because on API < 29 Android may pick them)
     * and the group, or null when the framework refused it.
     */
    private fun tryCreateGroup(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        wantedSsid: String,
        wantedPass: String,
        log: (String) -> Unit
    ): CreatedGroup? {
        val config = buildGroupConfig(wantedSsid, wantedPass, log)
        val latch = CountDownLatch(1)
        var failure = -1
        var refusedReason: Int? = null

        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.i(AppLog.TAG_AP, "p2p: createGroup accepted")
                latch.countDown()
            }

            override fun onFailure(reason: Int) {
                refusedReason = reason
                failure = reason
                AppLog.w(AppLog.TAG_AP, "p2p: createGroup refused, reason=$reason (${p2pFailure(reason)})")
                latch.countDown()
            }
        }

        try {
            requestCreateGroup(manager, channel, config, listener)
        } catch (e: Throwable) {
            log("ap[p2p]: createGroup threw ${e.javaClass.simpleName}: ${e.message}")
            AppLog.e(AppLog.TAG_AP, "p2p: createGroup threw", e)
            return null
        }

        if (!latch.await(CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            log("ap[p2p]: no callback within ${CALLBACK_TIMEOUT_MS / 1000}s - giving up on this attempt")
            return null
        }
        if (failure != -1) {
            log("ap[p2p]: FAILED - ${p2pFailure(failure)}")
            hintForP2pFailure(refusedReason ?: failure, log)
            return null
        }

        val group = requestGroup(channel, log)
        val realSsid = group?.networkName ?: wantedSsid
        val realPass = group?.passphrase ?: wantedPass
        if (group == null) {
            log("ap[p2p]: createGroup succeeded but the group is not readable yet - adopting the requested credentials")
        }
        return CreatedGroup(
            ApHandle(
                kind = ApKind.WIFI_DIRECT,
                interfaceName = null,
                ssid = realSsid,
                password = realPass,
                detail = "WiFi Direct group (interface discovered separately)"
            ),
            group
        )
    }

    /**
     * API 29+ exposes `createGroup(channel, config, listener)` publicly. On API
     * 28 (the Hot 8) the same method exists but is hidden - call it by
     * reflection, and when that build has neither, fall back to the plain
     * createGroup and let Android pick the name and password (they are read
     * back and shown, so joining is still one tap).
     */
    private fun requestCreateGroup(
        manager: WifiP2pManager,
        channel: WifiP2pManager.Channel,
        config: WifiP2pConfig,
        listener: WifiP2pManager.ActionListener
    ) {
        if (Build.VERSION.SDK_INT >= 29) {
            manager.createGroup(channel, config, listener)
            return
        }
        try {
            val hidden = manager.javaClass.getMethod(
                "createGroup",
                WifiP2pManager.Channel::class.java,
                WifiP2pConfig::class.java,
                WifiP2pManager.ActionListener::class.java
            )
            hidden.invoke(manager, channel, config, listener)
            AppLog.i(AppLog.TAG_AP, "p2p: used the hidden createGroup(config) variant - name and password are ours")
        } catch (e: Throwable) {
            AppLog.i(
                AppLog.TAG_AP,
                "p2p: no createGroup(config) on this build (${e.javaClass.simpleName}) - " +
                    "Android picks the name and password; they will be read back and shown"
            )
            manager.createGroup(channel, listener)
        }
    }

    private fun buildGroupConfig(wantedSsid: String, wantedPass: String, log: (String) -> Unit): WifiP2pConfig {
        return if (Build.VERSION.SDK_INT >= 29) {
            WifiP2pConfig.Builder()
                .setNetworkName(wantedSsid)
                .setPassphrase(wantedPass)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                .enablePersistentMode(false)
                .build()
                .also { log("ap[p2p]: creating WiFi Direct group \"$wantedSsid\" (own name/password, 2.4GHz)") }
        } else {
            WifiP2pConfig().apply {
                networkName = wantedSsid
                passphrase = wantedPass
            }.also { log("ap[p2p]: creating WiFi Direct group \"$wantedSsid\" (own name/password)") }
        }
    }

    /**
     * Removes any existing group and waits until `requestGroupInfo` confirms
     * there is none. The caller only attempts createGroup after this returns
     * true, so a leftover group (reason=2 BUSY on every attempt) can never
     * block us.
     */
    private fun ensureCleanP2pState(channel: WifiP2pManager.Channel, log: (String) -> Unit): Boolean {
        var group = requestGroup(channel, log)
        if (group != null) {
            log("ap[p2p]: leftover group present (\"${group.networkName}\", owner=${group.isGroupOwner}) - removing before createGroup")
            if (!requestRemoveGroup(channel, log)) {
                log("ap[p2p]: removeGroup refused on this channel - dropping the channel and retrying once")
                p2pChannel = null
                val fresh = freshChannel(p2pManager, log) ?: return false
                group = requestGroup(fresh, log)
                if (group != null) {
                    if (!requestRemoveGroup(fresh, log) || requestGroup(fresh, log) != null) {
                        log("ap[p2p]: the leftover group survived removeGroup")
                        return false
                    }
                }
            }
        }
        // Give the P2P state machine a beat, then confirm with a fresh query.
        try {
            Thread.sleep(CLEAN_STATE_SETTLE_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        val confirm = requestGroup(channel, log)
        return if (confirm == null) {
            log("ap[p2p]: P2P state confirmed clean - no group")
            true
        } else {
            log("ap[p2p]: a group is still present after removeGroup (\"${confirm.networkName}\")")
            false
        }
    }

    private fun requestRemoveGroup(channel: WifiP2pManager.Channel, log: (String) -> Unit): Boolean {
        val manager = p2pManager ?: return false
        val latch = CountDownLatch(1)
        var ok = false
        try {
            manager.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    ok = true
                    AppLog.i(AppLog.TAG_AP, "p2p: removeGroup confirmed")
                    latch.countDown()
                }

                override fun onFailure(reason: Int) {
                    AppLog.w(AppLog.TAG_AP, "p2p: removeGroup failed reason=$reason (${p2pFailure(reason)})")
                    latch.countDown()
                }
            })
        } catch (e: Throwable) {
            log("ap[p2p]: removeGroup threw ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        if (!latch.await(REMOVE_GROUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            log("ap[p2p]: removeGroup never answered within ${REMOVE_GROUP_TIMEOUT_MS / 1000}s")
            return false
        }
        log(if (ok) "ap[p2p]: removeGroup confirmed" else "ap[p2p]: removeGroup failed")
        return ok
    }

    private fun freshChannel(manager: WifiP2pManager?, log: (String) -> Unit): WifiP2pManager.Channel? {
        if (manager == null) return null
        p2pChannel = null
        val channel = p2pChannel ?: manager.initialize(app, Looper.getMainLooper()) {
            p2pGroupActive = false
            AppLog.w(AppLog.TAG_AP, "p2p: channel disconnected - the WiFi Direct group is gone")
        }
        p2pChannel = channel
        return channel
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
        if (result == null) log("ap[p2p]: requestGroupInfo returned null (no group visible)")
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
        private const val REMOVE_GROUP_TIMEOUT_MS = 10_000L
        private const val INTERFACE_TIMEOUT_MS = 20_000L
        private const val CLEAN_STATE_SETTLE_MS = 1_000L

        /** WifiP2pManager.ActionListener error codes, in plain words. */
        fun p2pFailure(reason: Int): String = when (reason) {
            0 -> "ERROR - the framework refused (often: Location permission or Location services are off)"
            1 -> "P2P_UNSUPPORTED - this device has no WiFi Direct"
            2 -> "BUSY - the P2P state machine is not accepting a new group (WiFi off, Location off, a stale channel, or a leftover group - which is why we removeGroup first)"
            3 -> "NO_SERVICE_REQUESTS - service discovery was not registered"
            4 -> "NETWORK_ALREADY_CONNECTED - already in a WiFi Direct group"
            else -> "unknown reason $reason"
        }

        private fun hintForP2pFailure(reason: Int, log: (String) -> Unit) {
            when (reason) {
                0 -> log("ap[p2p]: hint - grant Location permission and switch Location ON; " +
                    "Android hides WiFi APIs from apps without it")
                1 -> log("ap[p2p]: hint - this phone has no WiFi Direct")
                2 -> log("ap[p2p]: hint - " +
                    "a leftover group or a disabled P2P state machine answers BUSY. The group is " +
                    "removed and the state confirmed clean before the retry; if it still answers " +
                    "BUSY, WiFi or Location is off.")
                4 -> log("ap[p2p]: hint - a WiFi Direct group already exists; it is removed and retried")
                else -> Unit
            }
        }
    }
}
