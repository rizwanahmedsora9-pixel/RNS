package com.hotspot.billing

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.content.SharedPreferences

/**
 * Shared constants and helpers for the onboarding flow:
 *
 * ```
 * Splash -> Root check -> (Continue) -> Hotspot settings -> Voucher system -> Start -> Dashboard
 * ```
 *
 * Each step is its own screen; nothing here touches root or the network, so the
 * wizard itself can never crash the app - validation failures are shown, not
 * thrown.
 */
object SetupFlow {

    const val PREF_SETUP_COMPLETE = "setup_complete"
    const val PREF_PLAN_PRESET = "plan_preset"
    const val PREF_PLAN_COUNT = "plan_count"
    const val PREF_PLAN_DOWN_MB = "plan_down_mb"
    const val PREF_PLAN_UP_MB = "plan_up_mb"

    fun isSetupComplete(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(PREF_SETUP_COMPLETE, false)

    fun setupPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(HotspotService.PREFS, Context.MODE_PRIVATE)

    /**
     * What comes after the root check. The settings screens are asked on a
     * first run; once setup is done, Continue goes straight to the dashboard
     * (the wizard stays reachable from Settings -> "Run setup wizard").
     */
    fun nextAfterRootCheck(context: Context): Class<out android.app.Activity> =
        if (isSetupComplete(setupPrefs(context))) MainActivity::class.java
        else SetupHotspotActivity::class.java

    // ------------------------------------------------------------------ validation
    //
    // The SSID/passphrase typed in the wizard end up in hostapd/WiFi
    // configuration fields AND in root shell commands. The F-01 audit finding
    // was exactly this: an unsanitised value can carry `"` `` ` `` `$` or `\`
    // into a uid-0 command. These checks reject such values up front, and the
    // shell layer (ApConfigText.sanitizeSsid/sanitizePassphrase) strips them a
    // second time as the last line of defence.

    private val UNSAFE_SHELL_CHARS = Regex("""["`$\\]""")

    /** WPA2 SSID: 1..32 printable ASCII characters, no shell-dangerous characters. */
    fun validateSsid(raw: String): String? {
        val s = raw.trim()
        return when {
            s.isEmpty() -> "Network name is empty."
            s.length > 32 -> "Network name is longer than 32 characters (WPA2 limit)."
            s.any { it.code < 0x20 || it.code > 0x7E } ->
                "Network name contains characters this phone cannot use - ASCII only."
            UNSAFE_SHELL_CHARS.containsMatchIn(s) ->
                "Network name contains \" ` \$ or \\ - remove them."
            else -> null
        }
    }

    /** WPA2 passphrase: 8..63 printable ASCII characters, no shell-dangerous characters. */
    fun validatePassphrase(raw: String): String? {
        val s = raw.trim()
        return when {
            s.isEmpty() -> "Password is empty."
            s.length < 8 -> "Password is shorter than 8 characters (WPA2 needs 8-63)."
            s.length > 63 -> "Password is longer than 63 characters (WPA2 limit)."
            s.any { it.code < 0x20 || it.code > 0x7E } ->
                "Password contains characters this phone cannot use - ASCII only."
            UNSAFE_SHELL_CHARS.containsMatchIn(s) ->
                "Password contains \" ` \$ or \\ - remove them."
            else -> null
        }
    }

    // ------------------------------------------------------- WiFi-sharing permissions
    //
    // Duplicated from MainActivity on purpose: the wizard runs before the
    // dashboard exists, and a helper that takes the activity as a parameter
    // would be more ceremony than it is worth.

    fun wifiSharePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

    fun hasWifiSharePermissions(context: Context): Boolean =
        wifiSharePermissions().all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
}

/** One selectable voucher plan preset. [label] is persisted verbatim. */
data class PlanPreset(val label: String, val minutes: Int)

object VoucherPresets {
    val ALL = listOf(
        PlanPreset("1 Hour", 60),
        PlanPreset("3 Hours", 180),
        PlanPreset("1 Day", 1440),
        PlanPreset("7 Days", 10080)
    )

    fun from(label: String?): PlanPreset =
        ALL.firstOrNull { it.label == label } ?: ALL.first()

    /** The name stored on every generated voucher. */
    fun planName(preset: PlanPreset, downMbps: Int, upMbps: Int): String =
        "${preset.label} · ${downMbps}/${upMbps} Mbps"
}
