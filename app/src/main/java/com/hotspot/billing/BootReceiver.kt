package com.hotspot.billing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hotspot.billing.debug.AppLog

/**
 * Brings the gateway back up after a reboot so a power cut does not leave the
 * neighborhood offline until somebody remembers to open the app.
 *
 * Only once the setup wizard has been completed: on a fresh install there is
 * nothing configured yet, and starting a default-configured gateway from BOOT
 * COMPLETED would pre-empt the operator's own setup (and run it with a
 * password the operator never chose).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            val prefs = context.getSharedPreferences(HotspotService.PREFS, Context.MODE_PRIVATE)
            if (!SetupFlow.isSetupComplete(prefs)) {
                AppLog.i(
                    AppLog.TAG_SERVICE,
                    "boot: setup not complete yet - not starting the gateway (open the app to set it up)"
                )
                return
            }
            AppLog.i(AppLog.TAG_SERVICE, "boot: starting the gateway")
            context.startForegroundService(
                Intent(context, HotspotService::class.java).setAction(HotspotService.ACTION_START)
            )
        } catch (e: Throwable) {
            // A receiver must never crash the boot broadcast queue.
            AppLog.e(AppLog.TAG_SERVICE, "boot: could not restart the gateway", e)
        }
    }
}
