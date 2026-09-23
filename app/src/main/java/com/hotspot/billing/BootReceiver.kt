package com.hotspot.billing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the gateway back up after a reboot so a power cut does not leave the
 * neighborhood offline until somebody remembers to open the app.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(
                Intent(context, HotspotService::class.java).setAction(HotspotService.ACTION_START)
            )
        }
    }
}
