package com.hotspot.billing

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.debug.CrashGuard
import com.hotspot.billing.util.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Screen 1 - the root check. Runs on every launch (the gateway is uid-0
 * shell commands from start to stop, so without root there is nothing to
 * run).
 *
 * The Continue button is disabled until the check comes back confirmed
 * (`Shell.getShell().isRoot` == true) - that is the "ask Continue when root
 * is confirmed" step of the flow. When root is missing the button stays
 * disabled and the screen says exactly where to fix it, with a re-check.
 */
class RootCheckActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CrashGuard.handler())

    private lateinit var stepView: TextView
    private lateinit var titleView: TextView
    private lateinit var detailView: TextView
    private lateinit var boxView: View
    private lateinit var progress: ProgressBar
    private lateinit var btnContinue: Button
    private lateinit var btnRetry: Button

    private var checking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_root_check)
        } catch (e: Throwable) {
            AppLog.e(AppLog.TAG_UI, "root check: layout failed", e)
            finish()
            return
        }

        stepView = findViewById(R.id.root_step)
        titleView = findViewById(R.id.root_box_title)
        detailView = findViewById(R.id.root_detail)
        boxView = findViewById(R.id.root_status_box)
        progress = findViewById(R.id.root_progress)
        btnContinue = findViewById(R.id.btn_continue)
        btnRetry = findViewById(R.id.btn_retry)

        val wizard = !SetupFlow.isSetupComplete(SetupFlow.setupPrefs(this))
        stepView.text = if (wizard) "STEP 1 OF 4" else "CHECK BEFORE EVERY START"

        findViewById<TextView>(R.id.root_debugger).setOnClickListener { openDebugger() }
        btnContinue.setOnClickListener {
            AppLog.i(AppLog.TAG_UI, "setup: root confirmed - Continue tapped")
            val target = SetupFlow.nextAfterRootCheck(this)
            try {
                startActivity(Intent(this, target))
                finish()
            } catch (e: Throwable) {
                AppLog.e(AppLog.TAG_UI, "root check: could not open the next screen", e)
                detailView.text = "Could not open the next screen - see the debugger (error log)."
            }
        }
        btnRetry.setOnClickListener { checkRoot() }

        // Disabled until the check comes back with root confirmed.
        btnContinue.isEnabled = false
        checkRoot()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun checkRoot() {
        if (checking) return
        checking = true

        progress.visibility = View.VISIBLE
        paintBox(statusColor(), "Checking root access...")
        detailView.text = "Asking Magisk for a uid-0 shell. This takes a couple of seconds on first use."
        btnContinue.isEnabled = false
        btnRetry.isEnabled = false
        AppLog.i(AppLog.TAG_UI, "setup: root check started")

        scope.launch {
            // withTimeoutOrNull: on a phone where `su` exists but hangs, the
            // check must end (as "not available") instead of spinning forever.
            val ok = withContext(Dispatchers.IO) {
                try {
                    withTimeoutOrNull(ROOT_CHECK_TIMEOUT_MS) { RootShell.isRootAvailable() }
                } catch (e: Throwable) {
                    AppLog.e(AppLog.TAG_UI, "root check: the check itself threw", e)
                    false
                }
            }
            if (isFinishing || isDestroyed) return@launch

            checking = false
            progress.visibility = View.GONE
            btnRetry.isEnabled = true

            if (ok == true) {
                paintBox(ContextCompat.getColor(this@RootCheckActivity, R.color.green))
                titleView.text = "Root confirmed"
                detailView.text =
                    "This app runs the whole gateway (NAT, DHCP, firewall, portal, bandwidth " +
                        "caps) through root shell commands - with root confirmed it can start."
                btnContinue.isEnabled = true
                btnRetry.visibility = View.GONE
                AppLog.i(AppLog.TAG_UI, "setup: root CONFIRMED - the Continue button is enabled")
            } else {
                paintBox(ContextCompat.getColor(this@RootCheckActivity, R.color.red))
                titleView.text = if (ok == null) "Root check timed out" else "No root access"
                detailView.text =
                    if (ok == null)
                        "The root shell answered too slowly. Re-check, or open the debugger to see " +
                            "what the shell did."
                    else
                        "Grant 'Hotspot Billing' in Magisk (Magisk app -> Superuser), then press " +
                            "Check again. Without root the app cannot run the gateway - but the " +
                            "debugger still works and records every attempt."
                btnContinue.isEnabled = false
                btnRetry.visibility = View.VISIBLE
                AppLog.e(AppLog.TAG_UI, "setup: root NOT available (timeout=${ok == null}) - Continue stays hidden")
            }
        }
    }

    private fun statusColor() = ContextCompat.getColor(this, R.color.accentSoft)

    private fun paintBox(color: Int, title: String? = null) {
        boxView.setBackgroundColor(color)
        if (title != null) titleView.text = title
    }

    private fun openDebugger() {
        AppLog.i(AppLog.TAG_UI, "setup: opening the debugger from the root check")
        try {
            startActivity(DebugActivity.intent(this))
        } catch (e: Throwable) {
            AppLog.e(AppLog.TAG_UI, "root check: could not open the debugger", e)
        }
    }

    private companion object {
        const val ROOT_CHECK_TIMEOUT_MS = 20_000L
    }
}
