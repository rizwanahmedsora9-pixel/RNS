package com.hotspot.billing

import android.app.Application
import android.os.Build
import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.debug.CrashGuard

/**
 * Starts the recorder before anything else in the process, so a crash in
 * `MainActivity.onCreate` or during service startup is still captured - which is
 * exactly when a crash is most worth capturing.
 */
class RnsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        CrashGuard.install(this, versionLabel())
        AppLog.i(
            AppLog.TAG_SERVICE,
            "process started: pid=${android.os.Process.myPid()} ${Build.MANUFACTURER} ${Build.MODEL} " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) app ${versionLabel()}"
        )
    }

    private fun versionLabel(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        "${info.versionName}"
    } catch (e: Exception) {
        "unknown"
    }
}
