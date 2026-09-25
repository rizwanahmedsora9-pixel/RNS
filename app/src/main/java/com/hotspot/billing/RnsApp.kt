package com.hotspot.billing

import android.app.Application
import android.os.Build
import android.os.Process
import android.util.Log
import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.debug.CrashGuard

/**
 * Starts the recorder before anything else in the process, so a crash in an
 * Activity's onCreate or during service start-up is still captured - which is
 * exactly when a crash is most worth capturing.
 *
 * The "crashed before it ever opened" failure the app used to have is a crash
 * in THIS method: anything here threw before the crash guard existed, and the
 * process died with no trace. So the order is deliberate and every step is
 * wrapped:
 *
 *  1. [AppLog.init] - the recorder (its own failure can only go to logcat);
 *  2. [CrashGuard.install] - from this point on, no uncaught exception on any
 *     thread dies without first being written to files/logs/last_crash.txt
 *     together with the 120 log records before it;
 *  3. [SoftApController.attach] - the framework AP reader (best-effort; its
 *     callers handle "not attached").
 *
 * Nothing after step 2 can take the process down without leaving a readable
 * error log behind.
 */
class RnsApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // 1. The recorder first.
        try {
            AppLog.init(this)
        } catch (e: Throwable) {
            // If even the recorder cannot start, the only other log is logcat.
            Log.e("RnsApp", "AppLog.init failed: ${e.javaClass.name}: ${e.message}")
        }

        // 2. The crash guard second.
        try {
            CrashGuard.install(this, versionLabel())
        } catch (e: Throwable) {
            // Last resort: still make sure the next uncaught exception lands in
            // the app log and the file, then let the platform handler do its
            // normal thing.
            installMinimalCrashHandler()
            AppLog.e(AppLog.TAG_CRASH, "CrashGuard.install failed - a minimal handler is in place instead", e)
        }

        // 3. The AP layer asks the framework (getWifiApState / requestGroupInfo)
        // whether something is beaconing; that needs the application Context and
        // must be ready before ANY code path can ask - a "Detect" tap, a
        // notification action, or the gateway service. Wrapped: a failure here
        // only means "framework state unknown", which every caller handles.
        try {
            com.hotspot.billing.net.SoftApController.attach(this)
        } catch (e: Throwable) {
            AppLog.w(AppLog.TAG_AP, "SoftApController.attach failed (non-fatal): ${e.javaClass.name}: ${e.message}")
        }

        AppLog.i(
            AppLog.TAG_SERVICE,
            "process started: pid=${Process.myPid()} ${Build.MANUFACTURER} ${Build.MODEL} " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) app ${versionLabel()}"
        )
    }

    private fun installMinimalCrashHandler() {
        try {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    AppLog.e(
                        AppLog.TAG_CRASH,
                        "uncaught on ${thread.name}: ${throwable.javaClass.name}: ${throwable.message}\n" +
                            AppLog.stackTrace(throwable)
                    )
                } catch (ignored: Throwable) {
                    // logging must never block the crash path
                }
                try {
                    previous?.uncaughtException(thread, throwable)
                } catch (ignored: Throwable) {
                    Process.killProcess(Process.myPid())
                    kotlin.system.exitProcess(10)
                }
            }
        } catch (ignored: Throwable) {
            // there is nothing left to do - logcat is the only witness
        }
    }

    private fun versionLabel(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        "${info.versionName}"
    } catch (e: Exception) {
        "unknown"
    }
}
