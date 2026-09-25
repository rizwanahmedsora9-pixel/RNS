package com.hotspot.billing

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hotspot.billing.db.AppDatabase
import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.net.ApMode
import com.hotspot.billing.net.VoucherManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen 4 - Ready. Shows exactly what Start will do (network, mode, voucher
 * plan) and then:
 *
 *  1. generates the voucher codes (a failed generation is logged and shown,
 *     but never blocks the gateway from starting);
 *  2. marks setup complete, so the next launch skips the wizard;
 *  3. starts the gateway foreground service;
 *  4. opens the dashboard.
 */
class SetupStartActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { SetupFlow.setupPrefs(this) }

    private lateinit var btnStart: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_setup_start)
        } catch (e: Throwable) {
            AppLog.e(AppLog.TAG_UI, "setup start: layout failed", e)
            finish()
            return
        }

        bindSummary()

        val firstRun = !SetupFlow.isSetupComplete(prefs)
        btnStart = findViewById(R.id.btn_start_gw)
        btnStart.text = if (firstRun) "START GATEWAY" else "SAVE & RESTART GATEWAY"
        btnStart.setOnClickListener { start() }

        wireBack()
        findViewById<TextView>(R.id.st_debugger).setOnClickListener { openDebugger() }
        findViewById<Button>(R.id.btn_start_back).setOnClickListener { finish() }

        AppLog.i(
            AppLog.TAG_UI,
            "setup: ready screen shown (step 4 of 4, firstRun=$firstRun)"
        )
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun bindSummary() {
        findViewById<TextView>(R.id.st_ssid).text =
            prefs.getString(HotspotService.KEY_SSID, HotspotService.DEFAULT_SSID)
        findViewById<TextView>(R.id.st_pass).text =
            prefs.getString(HotspotService.KEY_PASS, HotspotService.DEFAULT_PASS)
        findViewById<TextView>(R.id.st_mode).text =
            ApMode.from(prefs.getString(HotspotService.KEY_AP_MODE, ApMode.AUTO.key)).label
        val preset = VoucherPresets.from(prefs.getString(SetupFlow.PREF_PLAN_PRESET, null))
        val down = prefs.getString(SetupFlow.PREF_PLAN_DOWN_MB, "2")
        val up = prefs.getString(SetupFlow.PREF_PLAN_UP_MB, "1")
        val count = prefs.getString(SetupFlow.PREF_PLAN_COUNT, "20")
        findViewById<TextView>(R.id.st_plan).text =
            VoucherPresets.planName(preset, down?.toIntOrNull() ?: 2, up?.toIntOrNull() ?: 1)
        findViewById<TextView>(R.id.st_count).text = count
    }

    private fun start() {
        btnStart.isEnabled = false
        btnStart.text = "Starting..."
        AppLog.i(AppLog.TAG_UI, "setup: Start tapped - generating vouchers and starting the gateway")

        scope.launch {
            // 1. Mint the codes first, on a background thread.
            val codes = withContext(Dispatchers.IO) { generateVouchers() }

            // 2. Setup is done from here on: future launches go
            //    splash -> root check -> dashboard.
            prefs.edit().putBoolean(SetupFlow.PREF_SETUP_COMPLETE, true).apply()
            AppLog.i(AppLog.TAG_UI, "setup: complete - the wizard will not show again unless re-run from Settings")

            // 3. Bring the gateway up. This is the real start.
            try {
                startForegroundService(
                    Intent(this@SetupStartActivity, HotspotService::class.java)
                        .setAction(HotspotService.ACTION_START)
                )
                AppLog.i(AppLog.TAG_UI, "setup: gateway service started (ACTION_START)")
            } catch (e: Throwable) {
                AppLog.e(AppLog.TAG_UI, "setup: could not start the gateway service - the dashboard can retry", e)
                toast("Gateway did not start - open the app and press Start there. The reason is in the debugger.")
            }

            // 4. Hand the operator the codes, then go to the live dashboard.
            if (codes.isNotEmpty()) {
                showCodes(codes)
            } else {
                toast("No voucher codes could be generated - open the Vouchers tab or the debugger.")
                goDashboard()
            }
        }
    }

    private var codesDialog: AlertDialog? = null

    private fun goDashboard() {
        codesDialog = null
        try {
            startActivity(Intent(this, MainActivity::class.java))
        } catch (e: Throwable) {
            AppLog.e(AppLog.TAG_UI, "setup: could not open the dashboard", e)
        }
        finish()
    }

    /**
     * While the codes dialog is up, the back button behaves like its
     * "Open dashboard" button instead of losing the wizard's tail end.
     */
    private fun wireBack() {
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val dialog = codesDialog
                if (dialog?.isShowing == true) {
                    dialog.dismiss()
                    isEnabled = false
                    goDashboard()
                } else {
                    isEnabled = false
                    finish()
                }
            }
        })
    }

    /** @return the freshly generated codes, or an empty list with the reason in the log. */
    private fun generateVouchers(): List<String> = try {
        val db = AppDatabase.get(applicationContext)
        val manager = VoucherManager(db)
        val preset = VoucherPresets.from(prefs.getString(SetupFlow.PREF_PLAN_PRESET, null))
        val downKbit = (prefs.getString(SetupFlow.PREF_PLAN_DOWN_MB, "2")?.toIntOrNull() ?: 2) * 1000
        val upKbit = (prefs.getString(SetupFlow.PREF_PLAN_UP_MB, "1")?.toIntOrNull() ?: 1) * 1000
        val count = prefs.getString(SetupFlow.PREF_PLAN_COUNT, "20")?.toIntOrNull() ?: 20
        val name = VoucherPresets.planName(preset, downKbit / 1000, upKbit / 1000)
        manager.generateBatch(count, name, preset.minutes, downKbit, upKbit)
    } catch (e: Throwable) {
        AppLog.e(AppLog.TAG_VOUCHER, "setup: voucher generation failed - the gateway will still start", e)
        emptyList()
    }

    private fun showCodes(codes: List<String>) {
        val sample = codes.take(10).joinToString("\n")
        val dialog = AlertDialog.Builder(this)
            .setTitle("Your first voucher codes")
            .setMessage(
                "$sample" +
                    (if (codes.size > 10) "\n...and ${codes.size - 10} more in the Vouchers tab" else "") +
                    "\n\nCustomers type these on the sign-in page when they join the network."
            )
            .setPositiveButton("Open dashboard") { _, _ -> goDashboard() }
            .setCancelable(true)
            .show()
        // Tapping outside just closes the dialog: make Start usable again
        // instead of leaving the button stuck on "Starting...".
        dialog.setOnDismissListener {
            btnStart.isEnabled = true
            btnStart.text = "SAVE & RESTART GATEWAY"
        }
        codesDialog = dialog
    }

    private fun openDebugger() {
        AppLog.i(AppLog.TAG_UI, "setup: opening the debugger from the start step")
        try {
            startActivity(DebugActivity.intent(this))
        } catch (e: Throwable) {
            AppLog.e(AppLog.TAG_UI, "setup start: could not open the debugger", e)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
