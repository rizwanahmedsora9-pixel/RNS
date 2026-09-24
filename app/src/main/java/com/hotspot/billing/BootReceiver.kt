package com.hotspot.billing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Brings the gateway back up after a reboot so a power cut does not leave the
 * neighborhood offline until somebody remembers to open the app - UNLESS the
 * user's last action was a manual stop. That decision is persisted by
 * [HotspotService] (KEY_MANUALLY_STOPPED), so "Stop" means "stop" across
 * reboots instead of silently restarting on its own.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences(HotspotService.PREFS, Context.MODE_PRIVATE)
            val manuallyStopped = prefs.getBoolean(HotspotService.KEY_MANUALLY_STOPPED, false)
            val serviceIntent = Intent(context, HotspotService::class.java)
            if (manuallyStopped) {
                // No action: the service starts and stays IDLE. The user's last
                // word was "stop", so a reboot does not override it.
                serviceIntent
            } else {
                serviceIntent.setAction(HotspotService.ACTION_START)
            }
            context.startForegroundService(serviceIntent)
        }
    }
}
