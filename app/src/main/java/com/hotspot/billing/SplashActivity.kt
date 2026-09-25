package com.hotspot.billing

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.debug.CrashGuard

/**
 * Screen 0 - the splash. This is the launcher activity.
 *
 * The app used to die before any screen appeared and left no trace; this
 * screen exists so that can no longer happen:
 *
 *  - every line that can throw is wrapped, and a failure is shown ON this
 *    screen and written to the error log instead of killing the process;
 *  - the crash saved by [CrashGuard] from the PREVIOUS run is shown here, so
 *    "it crashed before it opened" is readable the next time it opens;
 *  - routing is one hop: [RootCheckActivity] (the root check runs on every
 *    launch), and from there either the setup wizard (first run) or the
 *    dashboard (already configured).
 */
class SplashActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var statusView: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            setContentView(R.layout.activity_splash)
        } catch (e: Throwable) {
            // Even a broken layout must not kill the app: say what happened.
            AppLog.e(AppLog.TAG_UI, "splash: layout failed - the app keeps running on a blank screen", e)
            toast("Splash screen could not be drawn - open the debugger (error log) for the reason.")
            return
        }

        try {
            statusView = findViewById(R.id.splash_status)
            val version = findViewById<TextView>(R.id.splash_version)
            version.text = "v${appVersion()}  ·  ${Build.MANUFACTURER} ${Build.MODEL}  ·  Android ${Build.VERSION.RELEASE}"

            val crashBox = findViewById<TextView>(R.id.splash_crash)
            crashBox.setOnClickListener { openDebugger() }
            val crash = try {
                CrashGuard.lastCrashText()
            } catch (e: Throwable) {
                null
            }
            if (!crash.isNullOrBlank()) {
                crashBox.visibility = View.VISIBLE
                crashBox.text = "Last run ended with an error - tap to open the error log:\n\n" +
                    crash.lines().take(8).joinToString("\n")
                AppLog.w(AppLog.TAG_UI, "splash: showing the crash saved from the previous run")
            }

            statusView?.text = "Starting..."
            AppLog.i(
                AppLog.TAG_UI,
                "splash: shown - next is the root check (setup ${
                    if (SetupFlow.isSetupComplete(SetupFlow.setupPrefs(this))) "complete" else "pending"
                })"
            )
        } catch (e: Throwable) {
            AppLog.e(AppLog.TAG_UI, "splash: failed while showing state", e)
            statusView?.text = "Error while starting - open the debugger below for the reason."
        }

        // A short beat for the branding, then route. The router is wrapped too:
        // a routing failure must end on this screen, not in a dead app.
        handler.postDelayed({
            try {
                if (isFinishing || isDestroyed) return@postDelayed
                startActivity(Intent(this, RootCheckActivity::class.java))
                finish()
            } catch (e: Throwable) {
                AppLog.e(AppLog.TAG_UI, "splash: could not open the root check screen", e)
                statusView?.text = "Could not open the next screen - see the debugger."
            }
        }, SPLASH_MS)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun openDebugger() {
        AppLog.i(AppLog.TAG_UI, "setup: opening the debugger from the splash")
        try {
            startActivity(DebugActivity.intent(this))
        } catch (e: Throwable) {
            toast("Could not open the debugger - see logcat")
        }
    }

    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Throwable) {
        "?"
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val SPLASH_MS = 1_200L
    }
}
