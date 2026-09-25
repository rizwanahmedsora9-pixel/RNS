package com.hotspot.billing

import android.content.Context
import java.io.File

/**
 * Shared helpers for the emulator end-to-end tests.
 *
 * The suite drives the REAL app: real activities, the real gateway service,
 * the real captive portal and the real voucher database. Nothing is mocked,
 * because the point is to catch what a user on a phone would hit - a crash
 * during the wizard, a gateway start that dies before the hotspot appears, a
 * portal that stops answering the OS probe, a voucher that cannot be redeemed,
 * kicked and re-appointed.
 */
internal object E2E {

    /** The wizard's shared preferences (same file [HotspotService] reads). */
    fun prefs(context: Context) =
        context.getSharedPreferences(HotspotService.PREFS, Context.MODE_PRIVATE)

    /** The file [com.hotspot.billing.debug.CrashGuard] writes on any crash. */
    fun crashFile(context: Context): File? =
        com.hotspot.billing.debug.AppLog.crashFile()

    /** Simulate a first run: the wizard shows, setup is not complete. */
    fun firstRun(context: Context) {
        prefs(context).edit().putBoolean(SetupFlow.PREF_SETUP_COMPLETE, false).apply()
    }

    /** Simulate an already-configured install: the dashboard starts the gateway. */
    fun setupComplete(context: Context) {
        prefs(context).edit().putBoolean(SetupFlow.PREF_SETUP_COMPLETE, true).apply()
    }

    /** A free TCP port for the portal test (0.0.0.0:8080 may be taken). */
    fun freePort(): Int {
        val socket = java.net.ServerSocket(0)
        return try {
            socket.localPort
        } finally {
            socket.close()
        }
    }
}
