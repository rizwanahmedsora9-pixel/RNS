package com.hotspot.billing

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.net.ApMode
import com.hotspot.billing.util.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Screen 2 - the hotspot: the network customers join (name + password) and
 * HOW it is created (the AP mode). Values are validated here - the same
 * restrictions the root shell layer enforces - and saved to the shared
 * prefs the gateway service reads.
 */
class SetupHotspotActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs: SharedPreferences by lazy { SetupFlow.setupPrefs(this) }

    private lateinit var etSsid: EditText
    private lateinit var etPass: EditText
    private lateinit var etWan: EditText
    private lateinit var etLan: EditText
    private lateinit var errorView: TextView
    private lateinit var modeGroup: RadioGroup
    private lateinit var modeInfo: TextView

    private val modeButtons = LinkedHashMap<ApMode, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_setup_hotspot)
        } catch (e: Throwable) {
            AppLog.e(AppLog.TAG_UI, "setup hotspot: layout failed", e)
            finish()
            return
        }

        etSsid = findViewById(R.id.hs_ssid)
        etPass = findViewById(R.id.hs_pass)
        etWan = findViewById(R.id.hs_wan)
        etLan = findViewById(R.id.hs_lan)
        errorView = findViewById(R.id.hs_error)
        modeGroup = findViewById(R.id.hs_mode)
        modeInfo = findViewById(R.id.hs_mode_info)

        modeButtons[ApMode.AUTO] = R.id.hs_mode_auto
        modeButtons[ApMode.NETSHARE] = R.id.hs_mode_netshare
        modeButtons[ApMode.LOCAL_ONLY] = R.id.hs_mode_localonly
        modeButtons[ApMode.SYSTEM] = R.id.hs_mode_system
        modeButtons[ApMode.ROOT_AP] = R.id.hs_mode_rootap
        modeButtons[ApMode.MANUAL] = R.id.hs_mode_manual

        // Pre-fill with whatever is stored (defaults on a first run).
        etSsid.setText(prefs.getString(HotspotService.KEY_SSID, HotspotService.DEFAULT_SSID))
        etPass.setText(prefs.getString(HotspotService.KEY_PASS, HotspotService.DEFAULT_PASS))
        etWan.setText(prefs.getString(HotspotService.KEY_WAN_IF, ""))
        etLan.setText(prefs.getString(HotspotService.KEY_LAN_IF, ""))
        val mode = ApMode.from(prefs.getString(HotspotService.KEY_AP_MODE, ApMode.AUTO.key))
        modeButtons[mode]?.let { modeGroup.check(it) }
        updateModeInfo(selectedMode())

        modeGroup.setOnCheckedChangeListener { _, _ -> updateModeInfo(selectedMode()) }

        wireDetect()
        findViewById<Button>(R.id.hs_debugger).setOnClickListener { openDebugger() }
        findViewById<Button>(R.id.btn_hs_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_hs_next).setOnClickListener { next() }

        AppLog.i(AppLog.TAG_UI, "setup: hotspot screen shown (step 2 of 4)")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun selectedMode(): ApMode =
        modeButtons.entries.firstOrNull { it.value == modeGroup.checkedRadioButtonId }?.key
            ?: ApMode.AUTO

    private fun updateModeInfo(mode: ApMode) {
        modeInfo.text = mode.description
    }

    private fun next() {
        val ssid = etSsid.text.toString().trim()
        val pass = etPass.text.toString().trim()

        val ssidError = SetupFlow.validateSsid(ssid)
        val passError = SetupFlow.validatePassphrase(pass)
        val firstError = ssidError ?: passError
        if (firstError != null) {
            errorView.visibility = View.VISIBLE
            errorView.text = firstError
            AppLog.w(AppLog.TAG_UI, "setup: hotspot not saved - $firstError")
            toast(firstError)
            return
        }
        errorView.visibility = View.GONE

        val mode = selectedMode()
        prefs.edit()
            .putString(HotspotService.KEY_SSID, ssid)
            .putString(HotspotService.KEY_PASS, pass)
            .putString(HotspotService.KEY_WAN_IF, etWan.text.toString().trim())
            .putString(HotspotService.KEY_LAN_IF, etLan.text.toString().trim())
            .putString(HotspotService.KEY_AP_MODE, mode.key)
            .apply()
        AppLog.i(
            AppLog.TAG_UI,
            "setup: hotspot saved - mode ${mode.key}, ssid \"$ssid\", pass ${pass.length} chars"
        )

        // NetShare-style modes need location; asking now means the first start
        // does not fail on a SecurityException.
        if (mode != ApMode.SYSTEM && mode != ApMode.MANUAL &&
            !SetupFlow.hasWifiSharePermissions(this)
        ) {
            requestPermissions(SetupFlow.wifiSharePermissions(), REQ_WIFI_SHARE)
        }

        startActivity(Intent(this, SetupVoucherActivity::class.java))
        finish()
    }

    /** Fills the advanced fields from what the phone currently has. */
    private fun wireDetect() {
        findViewById<Button>(R.id.btn_hs_detect).setOnClickListener {
            val button = findViewById<Button>(R.id.btn_hs_detect)
            button.isEnabled = false
            button.text = "Detecting..."
            scope.launch {
                val (wan, lan) = withContext(Dispatchers.IO) {
                    try {
                        RootShell.defaultRouteInterface() to com.hotspot.billing.net.SoftApController.apInterface(null)
                    } catch (e: Throwable) {
                        AppLog.e(AppLog.TAG_UI, "setup: detect failed", e)
                        null to null
                    }
                }
                if (isFinishing || isDestroyed) return@launch
                button.isEnabled = true
                button.text = "Detect"
                etWan.setText(wan ?: "")
                etLan.setText(lan ?: "")
                AppLog.i(AppLog.TAG_UI, "setup: detect filled wan=${wan ?: "?"} lan=${lan ?: "?"}")
            }
        }
    }

    private fun openDebugger() {
        AppLog.i(AppLog.TAG_UI, "setup: opening the debugger from the hotspot step")
        try {
            startActivity(DebugActivity.intent(this))
        } catch (e: Throwable) {
            AppLog.e(AppLog.TAG_UI, "setup hotspot: could not open the debugger", e)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private companion object {
        const val REQ_WIFI_SHARE = 101
    }
}
