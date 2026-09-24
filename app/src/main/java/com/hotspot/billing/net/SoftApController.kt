package com.hotspot.billing.net

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import com.hotspot.billing.debug.AppLog
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
 * On Android 9/10 (the Infinix Hot 8) none of those shell commands exist. The
 * framework method that does is IWifiManager.startSoftAp, which the app uid is
 * not allowed to call. [startAp] tries it as root via app_process, then (only
 * if the binder layout looks like Android 9) service call. The toggle remains
 * the manual fallback; [apInterface] detecting it is what makes that path
 * automatic.
 */
object SoftApController {

    /**
     * Set once from [com.hotspot.billing.net.ApLauncher] / the service so the
     * framework questions (is the softap ENABLED? do we own a WiFi Direct
     * group?) can be asked without threading a Context through every call site.
     */
    @Volatile private var appContext: Context? = null

    /**
     * Our own base.apk, from PackageManager - NOT from `pm path` as root.
     * The 2026-09-24 14:42 export shows `pm path com.hotspot.billing` waiting
     * 16 s for the root shell twice per start; the answer is a field the app
     * process already has.
     */
    @Volatile private var apkPathCache: String? = null

    fun attach(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (apkPathCache == null) {
            apkPathCache = try {
                app.packageManager.getApplicationInfo(app.packageName, 0).sourceDir
                    ?.takeIf { it.endsWith(".apk") }
            } catch (e: Throwable) {
                null
            }
        }
    }

    /** What the framework says about the softap, without root and without the shell. */
    class FrameworkAp(
        val state: Int?,
        val ssid: String?,
        val passphrase: String?
    )

    /** Reads `getWifiApState()` / the saved AP config by reflection (both are @hide). */
    fun frameworkAp(log: (String) -> Unit = {}): FrameworkAp {
        val wifi = appContext?.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) return FrameworkAp(null, null, null)
        val state = try {
            val method = wifi.javaClass.methods.firstOrNull {
                it.name == "getWifiApState" && it.parameterTypes.isEmpty()
            }
            (method?.invoke(wifi) as? Int)
        } catch (e: Throwable) {
            log("ap[system]: getWifiApState is not callable here (${e.javaClass.simpleName})")
            null
        }
        var ssid: String? = null
        var pass: String? = null
        // API 30+: SoftApConfiguration. API 26-29: WifiConfiguration. Both @hide
        // on the getters we need; both are read best-effort and only ever logged.
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                val method = wifi.javaClass.methods.firstOrNull { it.name == "getSoftApConfiguration" }
                val config = method?.invoke(wifi)
                ssid = config?.javaClass?.getMethod("getSsid")?.invoke(config) as? String
                pass = config?.javaClass?.getMethod("getPassphrase")?.invoke(config) as? String
            } catch (e: Throwable) {
                AppLog.d(AppLog.TAG_AP, "ap[system]: getSoftApConfiguration unreadable (${e.javaClass.simpleName})")
            }
        }
        if (ssid.isNullOrBlank()) {
            try {
                @Suppress("DEPRECATION")
                val method = wifi.javaClass.methods.firstOrNull { it.name == "getWifiApConfiguration" }
                @Suppress("DEPRECATION")
                val config = method?.invoke(wifi) as? WifiConfiguration
                ssid = config?.SSID?.trim()?.removeSurrounding("\"")?.takeIf { it.isNotBlank() }
                pass = config?.preSharedKey
            } catch (e: Throwable) {
                AppLog.d(AppLog.TAG_AP, "ap[system]: getWifiApConfiguration unreadable (${e.javaClass.simpleName})")
            }
        }
        return FrameworkAp(state, ssid, pass)
    }

    /**
     * The whole radio picture plus the framework's own answers, evaluated by
     * [ApEvidence]: which interface customers can join right now, and why.
     *
     * @return null when the root shell could not answer at all (busy / no root).
     *   That is "unknown", never "no AP".
     */
    fun apDecision(pin: String?, expectStart: Boolean = false): ApDecision? {
        val shell = try {
            RootShell.radioSnapshot()
        } catch (e: Throwable) {
            AppLog.w(AppLog.TAG_AP, "radio snapshot failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        } ?: return null

        val framework = frameworkAp()
        val share = shareForFacts()
        // Only ask about a WiFi Direct group when a p2p interface even exists,
        // and never ask it more than once a second: `requestGroupInfo` can take
        // up to 2 s to give up on a radio whose Direct stack is off, and this
        // runs inside the AP wait loop (every 400 ms).
        val hasP2p = shell.interfaces.any { ApEvidence.isP2pIface(shell, it.name) }
        val group = if (!hasP2p) {
            WifiShareAp.GroupFacts(null, null, null, null)
        } else {
            val now = System.currentTimeMillis()
            val cached = groupFactsCache
            if (cached != null && now - cached.first < GROUP_FACTS_TTL_MS) {
                cached.second
            } else {
                val facts = try {
                    share?.currentGroup() ?: WifiShareAp.GroupFacts(null, null, null, null)
                } catch (e: Throwable) {
                    AppLog.w(
                        AppLog.TAG_AP,
                        "WiFi Direct group query failed: ${e.javaClass.simpleName}: ${e.message}"
                    )
                    WifiShareAp.GroupFacts(null, null, null, null)
                }
                groupFactsCache = now to facts
                facts
            }
        }
        val full = shell.copy(
            frameworkApState = framework.state,
            frameworkApSsid = framework.ssid,
            frameworkApPassword = framework.passphrase,
            localOnlyReservation = share?.holdsLocalOnly() == true,
            p2pGroupOwner = group.isOwner,
            p2pGroupIface = group.iface,
            p2pGroupSsid = group.ssid,
            p2pGroupPassphrase = group.passphrase
        )
        return ApEvidence.evaluate(full, pin, expectStart)
    }

    /** The WiFi Direct helper is per-Context; the object keeps one for the queries. */
    @Volatile private var factsShare: WifiShareAp? = null

    /** Cached `requestGroupInfo` answer, so the AP wait loop does not queue them. */
    @Volatile private var groupFactsCache: Pair<Long, WifiShareAp.GroupFacts>? = null
    private const val GROUP_FACTS_TTL_MS = 1_000L

    /** null when [attach] has not run yet - the decision then rests on the shell alone. */
    private fun shareForFacts(): WifiShareAp? {
        factsShare?.let { return it }
        val context = appContext ?: return null
        return WifiShareAp(context).also { factsShare = it }
    }

    /** Logs the decision the way the debugger reads: proof first, rejections after. */
    fun logDecision(decision: ApDecision?, log: (String) -> Unit) {
        if (decision == null) {
            log("ap: the root shell did not answer - cannot tell whether a hotspot is up")
            return
        }
        if (decision.iface != null) {
            log("ap: ${decision.iface} is a real AP - ${decision.proof}")
        } else {
            log("ap: ${decision.proof}")
        }
        decision.rejected.forEach { log("ap: not adopted -> $it") }
    }

    /**
     * Tries every programmatic start; returns true if an AP interface came up
     * and stayed up.
     *
     * On Android 9/10 (the Hot 8) `cmd wifi start-softap` does not exist. The
     * framework method that does work is `IWifiManager.startSoftAp`, which a
     * normal app is not allowed to call. [app] is used for the in-process
     * attempt; the root `app_process` attempt does not need it.
     */
    fun startAp(ssid: String, pass: String, log: (String) -> Unit, app: Context? = null): String? {
        app?.let { attach(it) }
        val safeSsid = shellSafe(ssid.ifBlank { "RNS-Hotspot" })
        val safePass = shellSafe(pass)

        // The Hot 8 tears a softap down within ~200ms if its tether dnsmasq
        // cannot bind ("Address already in use" then stopSoftAp). Free port 53
        // before asking the framework to start.
        releaseStaleDnsmasq(log)
        raiseClientLimit(log)

        if (app != null) {
            tryFrameworkStart(app, safeSsid, safePass, log)
            awaitAp(log, QUICK_WAIT_MS)?.let { return it }
        }
        if (tryRootProcess(safeSsid, safePass, log)) {
            awaitAp(log, STABLE_WAIT_MS)?.let { return it }
            log("ap[system]: root startSoftAp did not leave an interface up")
        }
        if (tryBinderStart(log)) {
            awaitAp(log, STABLE_WAIT_MS)?.let { return it }
            log("ap[system]: service-call startSoftAp did not leave an interface up")
        }

        // AOSP syntax: start-softap <ssid> (open|wpa2|...) <passphrase>.
        // Android 12+. On Android 9 these commands are missing; they are still
        // logged so the debugger shows that, instead of a silent skip.
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
            awaitAp(log, QUICK_WAIT_MS)?.let { return it }
        }
        return null
    }

    /** Kill leftover dnsmasq so the framework's tether dnsmasq can bind. */
    fun releaseStaleDnsmasq(log: (String) -> Unit) {
        val res = try {
            RootShell.run("sh /data/local/tmp/setup_network.sh free-dns")
        } catch (e: Throwable) {
            log("ap[system]: could not free port 53 (${e.message})")
            return
        }
        val lines = (res.out + res.err).filter { it.isNotBlank() }
        if (lines.none { it.contains("free-dns complete") || it.contains("free-dns skipped") }) {
            log("ap[system]: free-dns subcommand missing - killing dnsmasq directly")
            try {
                RootShell.run("killall dnsmasq >/dev/null 2>&1; true")
            } catch (e: Throwable) {
                log("ap[system]: killall dnsmasq failed (${e.message})")
            }
        } else {
            lines.forEach { log("ap[system]: $it") }
        }
    }

    /**
     * The Hot 8's saved hotspot config had `max_num_sta=1` (one client). The
     * framework writes that into hostapd when it starts. These settings keys
     * are what Transsion/MediaTek builds read; unknown keys are harmless.
     */
    private fun raiseClientLimit(log: (String) -> Unit) {
        val keys = listOf(
            "system wifi_hotspot_max_client_num",
            "system wifi_ap_max_client",
            "global wifi_hotspot_max_client_num",
            "global soft_ap_max_clients"
        )
        for (key in keys) {
            try {
                RootShell.run("settings put $key 10", quiet = true)
            } catch (e: Throwable) {
                log("ap[system]: could not set $key (${e.message})")
            }
        }
        log("ap[system]: asked the system for up to 10 hotspot clients (saved config was max_num_sta=1)")
    }

    /** What the running hostapd was actually told. Logged, never fatal. */
    fun logHostapdLimits(log: (String) -> Unit) {
        val text = try {
            RootShell.run(
                "cat /data/vendor/wifi/hostapd/hostapd_ap0.conf " +
                    "/data/vendor/wifi/hostapd/hostapd.conf 2>/dev/null",
                quiet = true
            ).out.joinToString("\n")
        } catch (e: Throwable) {
            return
        }
        val max = Regex("""max_num_sta=(\d+)""").find(text)?.groupValues?.get(1)
        val ssid = Regex("""(?m)^ssid=(.*)$""").find(text)?.groupValues?.get(1)
            ?: Regex("""ssid2=([0-9a-fA-F]+)""").find(text)?.groupValues?.get(1)?.let { hexSsid(it) }
        if (ssid != null) log("ap[system]: hostapd ssid=$ssid")
        when (max) {
            null -> log("ap[system]: hostapd conf has no max_num_sta")
            "1" -> log(
                "ap[system]: hostapd max_num_sta=1 — this build will only accept ONE client. " +
                    "The saved hotspot limit was not raised."
            )
            else -> log("ap[system]: hostapd max_num_sta=$max")
        }
    }

    private fun hexSsid(hex: String): String = try {
        hex.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
    } catch (e: Throwable) {
        hex
    }

    @Suppress("DEPRECATION")
    private fun tryFrameworkStart(app: Context, ssid: String, pass: String, log: (String) -> Unit) {
        val wifi = app.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            log("ap[system]: no WifiManager")
            return
        }
        try {
            val cfg = WifiConfiguration().apply {
                SSID = ssid
                preSharedKey = pass
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA2_PSK)
            }
            val method = wifi.javaClass.methods.firstOrNull {
                it.name == "startSoftAp" && it.parameterTypes.size == 1
            }
            if (method == null) {
                log("ap[system]: WifiManager.startSoftAp is not on this build")
                return
            }
            val ok = method.invoke(wifi, cfg) as? Boolean
            log("ap[system]: WifiManager.startSoftAp (app uid) returned $ok")
        } catch (e: Throwable) {
            val cause = (e as? java.lang.reflect.InvocationTargetException)?.targetException ?: e
            log(
                "ap[system]: WifiManager.startSoftAp (app uid) threw " +
                    "${cause.javaClass.simpleName}: ${cause.message} - retrying as root"
            )
        }
    }

    /**
     * Runs [SoftApEntry] as root so the caller's uid is 0. That is allowed to
     * call startSoftAp; the app uid (10164 in the Hot 8 log) is not.
     */
    private fun tryRootProcess(ssid: String, pass: String, log: (String) -> Unit): Boolean {
        val apk = apkPath(log) ?: return false
        val cmd = "CLASSPATH=${shQuote(apk)} app_process /system/bin " +
            "com.hotspot.billing.net.SoftApEntry start ${shQuote(ssid)} ${shQuote(pass)}"
        val res = try {
            RootShell.run(cmd)
        } catch (e: Throwable) {
            log("ap[system]: app_process threw ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        val text = (res.out + res.err).filter { it.isNotBlank() }
        text.forEach { log("ap[system]: $it") }
        val joined = text.joinToString("\n")
        if (joined.contains("Could not find or load") || joined.contains("ClassNotFoundException")) {
            log("ap[system]: SoftApEntry was not on CLASSPATH - retrying -Djava.class.path")
            return runSoftApEntry(
                "app_process -Djava.class.path=${shQuote(apk)} /system/bin " +
                    "com.hotspot.billing.net.SoftApEntry start ${shQuote(ssid)} ${shQuote(pass)}",
                log
            )
        }
        return res.code == 0 || joined.contains("START=true") || joined.contains("START=1")
    }

    private fun runSoftApEntry(cmd: String, log: (String) -> Unit): Boolean {
        val res = try {
            RootShell.run(cmd)
        } catch (e: Throwable) {
            log("ap[system]: app_process threw ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        val text = (res.out + res.err).filter { it.isNotBlank() }
        text.forEach { log("ap[system]: $it") }
        val joined = text.joinToString("\n")
        if (joined.contains("Could not find or load") || joined.contains("ClassNotFoundException")) {
            log("ap[system]: SoftApEntry is not in this APK")
            return false
        }
        return res.code == 0 || joined.contains("START=true") || joined.contains("START=1") ||
            joined.contains("STOP=")
    }

    /**
     * Last resort on a stock Android 9 binder layout: transaction 47 is
     * getWifiApEnabledState (returns 10..14) and 41 is startSoftAp. If 47 does
     * not look like an AP state, the OEM moved the codes and we do not guess.
     */
    private fun tryBinderStart(log: (String) -> Unit): Boolean {
        val probe = try {
            RootShell.run("service call wifi 47")
        } catch (e: Throwable) {
            log("ap[system]: service call probe threw ${e.message}")
            return false
        }
        val dump = (probe.out + probe.err).joinToString("\n")
        val state = ApPlan.parseServiceCallInt(dump)
        log("ap[system]: service call wifi 47 -> ${state ?: "not a parcel"} (10..14 means getWifiApEnabledState)")
        if (!ApPlan.looksLikeApState(state)) {
            log("ap[system]: not calling startSoftAp by transaction code - this build's binder layout is not Android 9 AOSP")
            return false
        }
        val start = try {
            RootShell.run("service call wifi 41 i32 0")
        } catch (e: Throwable) {
            log("ap[system]: service call start threw ${e.message}")
            return false
        }
        val detail = (start.out + start.err).firstOrNull { it.isNotBlank() }?.trim() ?: ""
        log("ap[system]: service call wifi 41 i32 0 (startSoftAp, saved config) exit ${start.code}${if (detail.isNotEmpty()) " ($detail)" else ""}")
        return true
    }

    private fun apkPath(log: (String) -> Unit): String? {
        // PackageManager already knows where our own APK lives - no root, no
        // queueing. `pm path` cost 16 s behind a busy shell on 2026-09-24.
        apkPathCache?.let { return it }
        val lines = try {
            RootShell.run("pm path com.hotspot.billing", quiet = true).out
        } catch (e: Throwable) {
            log("ap[system]: pm path failed (${e.message})")
            return null
        }
        val path = lines.map { it.substringAfter("package:").trim() }
            .firstOrNull { it.endsWith("base.apk") || it.endsWith(".apk") }
        if (path.isNullOrBlank()) {
            log("ap[system]: pm path returned nothing (${lines.joinToString()})")
            return null
        }
        apkPathCache = path
        return path
    }

    /**
     * Wait until an AP interface is *evidently beaconing*, and still is
     * [stableMs] later. The Hot 8 beacons and then immediately runs stopSoftAp
     * when tether setup fails; a one-shot check would report success for an AP
     * that is already gone. "Evidently beaconing" is [ApEvidence]'s rule, not
     * `ip link` - otherwise `p2p0` (always up while WiFi is on) answers the
     * question before the real AP has even been asked to start.
     */
    private fun awaitAp(log: (String) -> Unit, timeoutMs: Long, stableMs: Long = 1200L): String? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var seen: String? = null
        var seenAt = 0L
        var rejectedOnce = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val decision = try {
                apDecision(pin = null, expectStart = true)
            } catch (e: Throwable) {
                null
            }
            val iface = decision?.iface
            if (iface != null) {
                if (seen != iface) {
                    seen = iface
                    seenAt = SystemClock.elapsedRealtime()
                    log("ap[system]: $iface appeared (${decision.proof}), confirming it stays up")
                } else if (SystemClock.elapsedRealtime() - seenAt >= stableMs) {
                    log("ap[system]: $iface stayed up")
                    logHostapdLimits(log)
                    nudgeClientLimit(iface, log)
                    return iface
                }
            } else if (seen != null) {
                log("ap[system]: $seen came up and was torn down (usually dnsmasq could not bind port 53)")
                seen = null
            } else if (!rejectedOnce && decision != null) {
                // Once per attempt: show *why* nothing qualifies, so a radio that
                // refuses does not read as a silent timeout.
                rejectedOnce = true
                logDecision(decision, log)
            }
            try {
                Thread.sleep(400)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return null
            }
        }
        return null
    }

    private fun shQuote(value: String): String = "'" + value.replace("'", "") + "'"

    /** Tries to switch the AP back off. Does not guess binder transaction codes. */
    fun stopAp(log: (String) -> Unit) {
        // `svc wifi disable-softap` exits 0 on the Hot 8 even when the subcommand
        // does not exist, so it is not proof the AP stopped.
        val apk = apkPath(log)
        if (apk != null) {
            val stopped = runSoftApEntry(
                "CLASSPATH=${shQuote(apk)} app_process /system/bin " +
                    "com.hotspot.billing.net.SoftApEntry stop",
                log
            )
            if (stopped && apInterface(null) == null) return
        }
        for (cmd in listOf("cmd wifi stop-softap", "ndc softap stopap")) {
            val res = RootShell.run(cmd)
            log("AP stop: $cmd -> exit ${res.code}")
        }
    }

    /**
     * The Hot 8's hostapd was started with max_num_sta=1. Best-effort only:
     * the vendor conf is not rewritten, and a failure here does not fail start.
     */
    private fun nudgeClientLimit(iface: String, log: (String) -> Unit) {
        if (!iface.matches(Regex("^[A-Za-z0-9._-]{1,15}$"))) {
            log("ap[system]: not nudging client limit on unexpected interface name")
            return
        }
        val cmds = listOf(
            "hostapd_cli -p /data/vendor/wifi/hostapd/sockets -i $iface set max_num_sta 10",
            "iwpriv $iface set MaxStaNum 10"
        )
        for (cmd in cmds) {
            try {
                val res = RootShell.run(cmd, quiet = true)
                val detail = (res.out + res.err).firstOrNull { it.isNotBlank() }?.trim() ?: ""
                log("ap[system]: $cmd -> exit ${res.code}${if (detail.isNotEmpty()) " ($detail)" else ""}")
            } catch (e: Throwable) {
                log("ap[system]: $cmd failed (${e.message})")
            }
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
     * Not "any interface whose link is up": [ApEvidence] requires positive
     * evidence that something is beaconing on it (framework softap ENABLED,
     * a WiFi Direct group we own, our hostapd, or a wired LAN with link).
     * `p2p0` on this MediaTek build is UP whenever WiFi is, which is how the
     * 2026-09-24 14:42 start adopted a dead interface and reported a hotspot
     * nobody could join.
     *
     * @return the proven interface, or null when nothing is beaconing - or when
     *   the shell is too busy to answer ("unknown"). Callers that care read
     *   [apDecision] for the reason.
     */
    fun apInterface(pinned: String?): String? = try {
        apDecision(pin = pinned.takeIf { it?.isNotEmpty() == true })?.iface
    } catch (e: Throwable) {
        AppLog.w(AppLog.TAG_AP, "AP interface check failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private const val QUICK_WAIT_MS = 2_500L
    private const val STABLE_WAIT_MS = 6_000L

    /** Drops every character that could terminate a double-quoted shell string. */
    private fun shellSafe(value: String): String = value.replace(Regex("[\"`$\\\\]"), "")
}
