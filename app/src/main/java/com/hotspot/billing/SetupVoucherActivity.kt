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

/**
 * Screen 3 - the voucher system: which plan duration the codes carry, at what
 * speed, and how many codes to generate. The generation itself happens on the
 * Start screen (so a "back" here never mints codes the operator never saw).
 */
class SetupVoucherActivity : AppCompatActivity() {

    private val prefs: SharedPreferences by lazy { SetupFlow.setupPrefs(this) }

    private lateinit var etDown: EditText
    private lateinit var etUp: EditText
    private lateinit var etCount: EditText
    private lateinit var previewView: TextView
    private lateinit var errorView: TextView
    private lateinit var presetGroup: RadioGroup

    private val presetButtons = LinkedHashMap<PlanPreset, Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_setup_voucher)
        } catch (e: Throwable) {
            AppLog.e(AppLog.TAG_UI, "setup voucher: layout failed", e)
            finish()
            return
        }

        etDown = findViewById(R.id.vz_down)
        etUp = findViewById(R.id.vz_up)
        etCount = findViewById(R.id.vz_count)
        previewView = findViewById(R.id.vz_preview)
        errorView = findViewById(R.id.vz_error)
        presetGroup = findViewById(R.id.vz_preset)

        presetButtons[VoucherPresets.ALL[0]] = R.id.vz_preset_1h
        presetButtons[VoucherPresets.ALL[1]] = R.id.vz_preset_3h
        presetButtons[VoucherPresets.ALL[2]] = R.id.vz_preset_1d
        presetButtons[VoucherPresets.ALL[3]] = R.id.vz_preset_7d

        // Pre-fill from the last setup (defaults: 1 Hour, 2/1 Mbps, 20 codes).
        val preset = VoucherPresets.from(prefs.getString(SetupFlow.PREF_PLAN_PRESET, null))
        presetButtons[preset]?.let { presetGroup.check(it) }
        etDown.setText(prefs.getString(SetupFlow.PREF_PLAN_DOWN_MB, "2"))
        etUp.setText(prefs.getString(SetupFlow.PREF_PLAN_UP_MB, "1"))
        etCount.setText(prefs.getString(SetupFlow.PREF_PLAN_COUNT, "20"))
        updatePreview()

        presetGroup.setOnCheckedChangeListener { _, _ -> updatePreview() }
        etDown.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updatePreview() }
        })
        etUp.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updatePreview() }
        })
        etCount.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) { updatePreview() }
        })

        findViewById<TextView>(R.id.vz_debugger).setOnClickListener { openDebugger() }
        findViewById<Button>(R.id.btn_vz_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_vz_next).setOnClickListener { next() }

        AppLog.i(AppLog.TAG_UI, "setup: voucher screen shown (step 3 of 4)")
    }

    private fun selectedPreset(): PlanPreset =
        presetButtons.entries.firstOrNull { it.value == presetGroup.checkedRadioButtonId }?.key
            ?: VoucherPresets.ALL.first()

    private fun parseSpeed(field: EditText, fallback: Int): Int =
        field.text.toString().trim().toIntOrNull() ?: fallback

    private fun updatePreview() {
        val preset = selectedPreset()
        val down = parseSpeed(etDown, 2)
        val up = parseSpeed(etUp, 1)
        val count = etCount.text.toString().trim().toIntOrNull() ?: 20
        previewView.text = "Plan: \"${VoucherPresets.planName(preset, down, up)}\"  ·  " +
            "${preset.minutes / 60} h  ·  $count codes"
    }

    private fun next() {
        val preset = selectedPreset()
        val down = etDown.text.toString().trim().toIntOrNull()
        val up = etUp.text.toString().trim().toIntOrNull()
        val count = etCount.text.toString().trim().toIntOrNull()

        val error = when {
            down == null || down < 1 || down > 900 -> "Download speed must be 1-900 Mbps."
            up == null || up < 1 || up > 900 -> "Upload speed must be 1-900 Mbps."
            count == null || count < 1 || count > 500 -> "Code count must be 1-500."
            else -> null
        }
        if (error != null) {
            errorView.visibility = View.VISIBLE
            errorView.text = error
            AppLog.w(AppLog.TAG_UI, "setup: vouchers not saved - $error")
            toast(error)
            return
        }
        errorView.visibility = View.GONE

        prefs.edit()
            .putString(SetupFlow.PREF_PLAN_PRESET, preset.label)
            .putString(SetupFlow.PREF_PLAN_DOWN_MB, down.toString())
            .putString(SetupFlow.PREF_PLAN_UP_MB, up.toString())
            .putString(SetupFlow.PREF_PLAN_COUNT, count.toString())
            .apply()
        AppLog.i(
            AppLog.TAG_UI,
            "setup: voucher plan saved - \"${VoucherPresets.planName(preset, down, up)}\", $count codes"
        )

        startActivity(Intent(this, SetupStartActivity::class.java))
        finish()
    }

    private fun openDebugger() {
        AppLog.i(AppLog.TAG_UI, "setup: opening the debugger from the voucher step")
        try {
            startActivity(DebugActivity.intent(this))
        } catch (e: Throwable) {
            AppLog.e(AppLog.TAG_UI, "setup voucher: could not open the debugger", e)
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
