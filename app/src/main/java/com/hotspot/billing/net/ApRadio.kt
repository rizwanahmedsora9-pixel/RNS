package com.hotspot.billing.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Runtime permission gate for the WiFi APIs that throw without it. */
object ApRadio {

    /**
     * Local-only hotspot and WiFi Direct both require this. On API 26-32 that
     * is coarse/fine location; the Hot 8 (API 28) throws
     * `SecurityException: UID does not have Coarse Location permission`
     * from startLocalOnlyHotspot when it is missing.
     */
    fun hasLocationPermission(context: Context): Boolean {
        val app = context.applicationContext
        val needed = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        return needed.all {
            app.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
