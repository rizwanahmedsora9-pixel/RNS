package com.hotspot.billing

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.hotspot.billing.db.AppDatabase
import com.hotspot.billing.db.DeviceProfile
import com.hotspot.billing.db.UserSession
import com.hotspot.billing.db.Voucher
import com.hotspot.billing.db.VoucherStatus
import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.debug.DebugExport
import com.hotspot.billing.debug.LogFormat
import com.hotspot.billing.net.ApMode
import com.hotspot.billing.net.LeaseParser
import com.hotspot.billing.net.SoftApController
import com.hotspot.billing.net.VoucherManager
import com.hotspot.billing.ui.ClientAdapter
import com.hotspot.billing.ui.ClientRow
import com.hotspot.billing.ui.ProfileAdapter
import com.hotspot.billing.ui.SessionAdapter
import com.hotspot.billing.ui.VoucherAdapter
import com.hotspot.billing.util.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The admin console: dashboard (state + event log), voucher minting/management,
 * connected users + saved device profiles + session history, and settings.
 * All gateway work happens in [HotspotService]; this activity only renders and
 * issues commands to it.
 */
class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val db by lazy { AppDatabase.get(this) }
    private val voucherManager by lazy { VoucherManager(db) }
    private val prefs by lazy { getSharedPreferences(HotspotService.PREFS, Context.MODE_PRIVATE) }

    private var svc: HotspotService? = null
    private var bound = false

    private lateinit var tabs: List<TextView>
    private lateinit var sections: List<View>

    // Dashboard
    private lateinit var dashRoot: TextView
    private lateinit var dashAp: TextView
    private lateinit var dashWan: TextView
    private lateinit var dashLan: TextView
    private lateinit var dashPortal: TextView
    private lateinit var dashClients: TextView
    private lateinit var dashVouchers: TextView
    private lateinit var dashHint: TextView
    private lateinit var dashLog: TextView
    private lateinit var dashApKind: TextView
    private lateinit var dashApSsid: TextView
    private lateinit var dashFindings: TextView
    private lateinit var dashDot: View
    private lateinit var headerPhase: TextView

    // Vouchers tab
    private lateinit var etPlan: EditText
    private lateinit var etCount: EditText
    private lateinit var etMinutes: EditText
    private lateinit var etRate: EditText
    private lateinit var etCeil: EditText
    private lateinit var filterButtons: Map<Button, VoucherStatus?>

    // Settings tab
    private lateinit var etSsid: EditText
    private lateinit var etPass: EditText
    private lateinit var etWan: EditText
    private lateinit var etLan: EditText
    private lateinit var setEnv: TextView
    private lateinit var apModeGroup: RadioGroup
    private val modeButtons = LinkedHashMap<ApMode, Int>()

    private lateinit var voucherAdapter: VoucherAdapter
    private lateinit var clientAdapter: ClientAdapter
    private lateinit var profileAdapter: ProfileAdapter
    private lateinit var sessionAdapter: SessionAdapter

    // Cached data - main thread only.
    private var allVouchers: List<Voucher> = emptyList()
    private var profiles: List<DeviceProfile> = emptyList()
    private var leases: List<LeaseParser.Lease> = emptyList()
    private var envText = ""
    private var voucherFilter: VoucherStatus? = null

    private val handler = Handler(Looper.getMainLooper())
    private var pollCount = 0

    private val poller = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            pollCount++
            renderDashboard()
            refreshData(heavy = pollCount % 2 == 0)
            handler.postDelayed(this, 1500)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            svc = (service as? HotspotService.LocalBinder)?.service()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
        }
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        wireTabs()
        wireDashboard()
        wireVouchers()
        wireUsers()
        wireSettings()
        loadSettingsIntoFields()
        requestNotificationPermissionIfNeeded()
        if (!hasWifiSharePermissions()) requestWifiSharePermissions()

        // The gateway starts with the app and survives it being swiped away.
        startForegroundService(
            Intent(this, HotspotService::class.java).setAction(HotspotService.ACTION_START)
        )
        bindService(Intent(this, HotspotService::class.java), connection, Context.BIND_AUTO_CREATE)

        handler.post(poller)
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        if (bound) {
            unbindService(connection)
            bound = false
        }
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- tabs

    private fun wireTabs() {
        tabs = listOf(
            findViewById(R.id.tab_dashboard),
            findViewById(R.id.tab_vouchers),
            findViewById(R.id.tab_users),
            findViewById(R.id.tab_settings)
        )
        sections = listOf(
            findViewById<View>(R.id.section_dashboard),
            findViewById<View>(R.id.section_vouchers),
            findViewById<View>(R.id.section_users),
            findViewById<View>(R.id.section_settings)
        )
        tabs.forEachIndexed { index, tab -> tab.setOnClickListener { selectTab(index) } }
        selectTab(0)
    }

    private fun selectTab(index: Int) {
        sections.forEachIndexed { i, section ->
            section.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        tabs.forEachIndexed { i, tab ->
            // state_selected drives bg_nav_item, nav_item_text and the icon tint,
            // so one call moves the whole item to its active look.
            tab.isSelected = i == index
            tab.setTextColor(
                ContextCompat.getColor(this, if (i == index) R.color.accent else R.color.textDim)
            )
            tab.alpha = if (i == index) 1f else 0.85f
        }
    }

    // ---------------------------------------------------------------- dashboard

    private fun wireDashboard() {
        dashRoot = findViewById(R.id.dash_root)
        dashAp = findViewById(R.id.dash_ap)
        dashWan = findViewById(R.id.dash_wan)
        dashLan = findViewById(R.id.dash_lan)
        dashPortal = findViewById(R.id.dash_portal)
        dashClients = findViewById(R.id.dash_clients)
        dashVouchers = findViewById(R.id.dash_vouchers)
        dashHint = findViewById(R.id.dash_hint)
        dashLog = findViewById(R.id.dash_log)
        dashApKind = findViewById(R.id.dash_ap_kind)
        dashApSsid = findViewById(R.id.dash_ap_ssid)
        dashFindings = findViewById(R.id.dash_findings)
        dashDot = findViewById(R.id.dash_dot)
        headerPhase = findViewById(R.id.header_phase)

        findViewById<Button>(R.id.btn_start).setOnClickListener {
            withService { it.startSequence() }
        }
        findViewById<Button>(R.id.btn_stop).setOnClickListener {
            withService { it.stopSequence() }
        }
        findViewById<Button>(R.id.btn_open_tether).setOnClickListener { openTetherSettings() }
        findViewById<Button>(R.id.btn_debugger).setOnClickListener { openDebugger() }
        findViewById<Button>(R.id.btn_copy_log).setOnClickListener { copyWholeLog() }
    }

    private fun openDebugger() {
        AppLog.i(AppLog.TAG_UI, "opening the debugger from the dashboard")
        startActivity(DebugActivity.intent(this))
    }

    /** One tap to get the whole event log onto the clipboard. */
    private fun copyWholeLog() {
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                buildString {
                    append("=== RNS hotspot gateway - event log ===\n")
                    append("exported : ").append(LogFormat.timestamp(System.currentTimeMillis())).append('\n')
                    append("device   : ").append(android.os.Build.MANUFACTURER).append(' ')
                        .append(android.os.Build.MODEL).append(" / Android ")
                        .append(android.os.Build.VERSION.RELEASE).append('\n')
                    svc?.snapshot()?.let { snap ->
                        append("phase    : ").append(snap.phase).append('\n')
                        append("ap       : ").append(snap.apKind ?: "-")
                            .append(" (mode ").append(snap.apMode ?: "-").append(")\n")
                        append("ssid     : ").append(snap.apSsid ?: "-")
                            .append(" password: ").append(snap.apPassword ?: "-").append('\n')
                        append("lan/wan  : ").append(snap.lanIf ?: "-").append(" / ")
                            .append(snap.wanIf ?: "-").append('\n')
                        append("gateway  : ").append(snap.gatewayIp ?: "-")
                            .append(" dhcp=").append(snap.dhcpOwner ?: "-").append('\n')
                    }
                    append("---- log ----\n")
                    append(AppLog.text(limit = 4_000))
                }
            }
            val chars = DebugExport.copyToClipboard(this@MainActivity, text)
            toast("Copied $chars characters - paste it into the chat")
        }
    }

    private fun renderDashboard() {
        val state = svc?.state
        if (state == null) {
            dashRoot.text = "..."
            paintPhase("Connecting to the gateway…", R.color.textDim)
            headerPhase.text = "…"
            return
        }

        dashRoot.text = when (state.rootOk) {
            null -> "checking..."
            true -> "granted"
            false -> "NOT granted - allow in Magisk"
        }

        val phase = when (state.phase) {
            HotspotService.Phase.STARTING -> "Starting the gateway…" to R.color.amber
            HotspotService.Phase.WAITING_AP -> "Hotspot is off" to R.color.red
            HotspotService.Phase.RUNNING -> "Hotspot running" to R.color.green
            HotspotService.Phase.STOPPING -> "Stopping…" to R.color.amber
            HotspotService.Phase.STOPPED -> "Stopped" to R.color.textDim
            HotspotService.Phase.ERROR -> "Error - see below" to R.color.red
        }
        paintPhase(phase.first, phase.second)
        headerPhase.text = state.phase.name

        dashApKind.text = state.apKind ?: (state.apMode?.let { "waiting ($it)" } ?: "-")
        dashApSsid.text = when {
            state.apSsid == null -> "-"
            state.apPassword == null -> state.apSsid ?: "-"
            else -> "${state.apSsid} / ${state.apPassword}"
        }
        dashFindings.text = when {
            state.findings.isEmpty() -> if (state.lastHealthCheck == null) "not checked yet" else "healthy"
            else -> state.findings.joinToString(" · ") { it.code }
        }
        dashFindings.setTextColor(
            ContextCompat.getColor(
                this,
                if (state.findings.any { it.level.priority >= com.hotspot.billing.debug.LogLevel.WARN.priority }) {
                    R.color.amber
                } else {
                    R.color.textPrimary
                }
            )
        )

        dashWan.text = state.wanIf ?: "-"
        dashLan.text = state.lanIf ?: "-"
        dashPortal.text = if (state.portalRunning) {
            "http://${state.gatewayIp ?: "10.66.0.1"}/"
        } else {
            "not running"
        }
        dashHint.visibility = if (state.message.isNotBlank()) View.VISIBLE else View.GONE
        if (state.message.isNotBlank()) dashHint.text = state.message

        // The dashboard shows the story so far; the debugger holds the whole
        // 400-line buffer. A 400-line wall of monospace is not a dashboard.
        val lines = svc?.dumpLog() ?: emptyList()
        dashLog.text = when {
            lines.isEmpty() -> "(no events yet)"
            lines.size > LOG_ON_DASH ->
                "(… ${lines.size - LOG_ON_DASH} older events - the debugger has them all)\n" +
                    lines.takeLast(LOG_ON_DASH).joinToString("\n")
            else -> lines.joinToString("\n")
        }
    }

    /** Hero status: the big label, its colour, and the live dot beside it. */
    private fun paintPhase(label: String, colorRes: Int) {
        val color = ContextCompat.getColor(this, colorRes)
        dashAp.text = label
        dashAp.setTextColor(color)
        headerPhase.setTextColor(color)
        dashDot.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
    }

    // ---------------------------------------------------------------- vouchers

    private fun wireVouchers() {
        etPlan = findViewById(R.id.gen_plan)
        etCount = findViewById(R.id.gen_count)
        etMinutes = findViewById(R.id.gen_minutes)
        etRate = findViewById(R.id.gen_rate)
        etCeil = findViewById(R.id.gen_ceil)

        val presets = mapOf(
            R.id.preset_1h to ("1 Hour - 2Mbps" to Triple(60, 2048, 4096)),
            R.id.preset_3h to ("3 Hours - 4Mbps" to Triple(180, 4096, 8192)),
            R.id.preset_24h to ("1 Day - 8Mbps" to Triple(1440, 8192, 16384)),
            R.id.preset_7d to ("7 Days - 10Mbps" to Triple(10080, 10240, 20480))
        )
        presets.forEach { (id, spec) ->
            findViewById<Button>(id).setOnClickListener {
                etPlan.setText(spec.first)
                etMinutes.setText(spec.second.first.toString())
                etRate.setText(spec.second.second.toString())
                etCeil.setText(spec.second.third.toString())
            }
        }

        filterButtons = mapOf(
            findViewById<Button>(R.id.filter_all) to null,
            findViewById<Button>(R.id.filter_unused) to VoucherStatus.UNUSED,
            findViewById<Button>(R.id.filter_active) to VoucherStatus.ACTIVE,
            findViewById<Button>(R.id.filter_expired) to VoucherStatus.EXPIRED
        )
        filterButtons.forEach { (button, status) ->
            button.setOnClickListener { setFilter(status) }
        }

        voucherAdapter = VoucherAdapter(
            onCopy = { copyText(it.code); toast("Copied ${it.code}") },
            onShare = { shareText(it.code) },
            onExpire = { voucher ->
                confirm(
                    "Expire ${voucher.code}?",
                    "The device using it is kicked off the internet immediately."
                ) {
                    scope.launch {
                        withContext(Dispatchers.IO) { voucherManager.forceExpire(voucher.code) }
                        refreshData(false)
                    }
                }
            },
            onDelete = { voucher ->
                confirm(
                    "Delete ${voucher.code}?",
                    "It disappears from the list permanently."
                ) {
                    scope.launch {
                        withContext(Dispatchers.IO) { voucherManager.deleteVoucher(voucher.code) }
                        refreshData(false)
                    }
                }
            }
        )
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.voucher_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = voucherAdapter
        }

        findViewById<Button>(R.id.btn_generate).setOnClickListener { generateVouchers() }
        setFilter(null)
    }

    private fun generateVouchers() {
        val count = etCount.text.toString().trim().toIntOrNull()
        val minutes = etMinutes.text.toString().trim().toIntOrNull()
        val rate = etRate.text.toString().trim().toIntOrNull()
        val ceil = etCeil.text.toString().trim().toIntOrNull()
        val plan = etPlan.text.toString().trim().ifEmpty { "Custom plan" }

        if (count == null || count < 1 || minutes == null || minutes < 1 ||
            rate == null || rate < 64 || ceil == null || ceil < rate
        ) {
            toast("Fill in: how many, minutes, and both speeds (max >= min)")
            return
        }

        scope.launch {
            val codes = withContext(Dispatchers.IO) {
                voucherManager.generateBatch(count.coerceAtMost(200), plan, minutes, rate, ceil)
            }
            showCodesDialog(codes)
            refreshData(false)
        }
    }

    private fun showCodesDialog(codes: List<String>) {
        val message = codes.joinToString("\n")
        val dialog = AlertDialog.Builder(this)
            .setTitle("${codes.size} voucher(s) created")
            .setMessage(message)
            .setPositiveButton("Copy all") { d, _ -> copyText(message); toast("Copied"); d.dismiss() }
            .setNeutralButton("Share") { d, _ -> shareText(message); d.dismiss() }
            .setNegativeButton("Close", null)
            .create()
        dialog.show()
        dialog.findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
    }

    private fun setFilter(status: VoucherStatus?) {
        voucherFilter = status
        filterButtons.forEach { (button, value) ->
            button.setTextColor(
                ContextCompat.getColor(
                    this,
                    if (value == status) R.color.accent else R.color.textDim
                )
            )
        }
        renderVoucherList()
    }

    // ---------------------------------------------------------------- users

    private fun wireUsers() {
        clientAdapter = ClientAdapter(
            onProfile = { row -> showProfileDialog(row.mac, row.label, null, null) },
            onKick = { row ->
                val voucher = row.voucher
                if (voucher != null) {
                    confirm(
                        "Kick this device?",
                        "${row.label ?: row.mac} loses internet now (voucher ${voucher.code} expires)."
                    ) {
                        scope.launch {
                            withContext(Dispatchers.IO) { voucherManager.forceExpire(voucher.code) }
                            refreshData(false)
                        }
                    }
                }
            }
        )
        profileAdapter = ProfileAdapter(
            onEdit = { profile ->
                showProfileDialog(profile.mac, profile.label, profile.phone, profile.note)
            },
            onDelete = { profile ->
                confirm(
                    "Delete profile?",
                    "The saved name/note for ${profile.mac} is removed. Vouchers are not affected."
                ) {
                    scope.launch {
                        withContext(Dispatchers.IO) { db.deviceProfileDao().delete(profile.mac) }
                        refreshData(false)
                    }
                }
            }
        )
        sessionAdapter = SessionAdapter()

        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.client_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = clientAdapter
        }
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.profile_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = profileAdapter
        }
        findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.session_list).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = sessionAdapter
        }
    }

    /** Dialog to create/edit the user profile attached to a device MAC. */
    private fun showProfileDialog(mac: String, label: String?, phone: String?, note: String?) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val etLabel = EditText(this).apply {
            hint = "Name / label (e.g. Ali - Samsung A12)"
            setText(label ?: "")
        }
        val etPhone = EditText(this).apply {
            hint = "Phone"
            inputType = InputType.TYPE_CLASS_PHONE
            setText(phone ?: "")
        }
        val etNote = EditText(this).apply {
            hint = "Note (e.g. regular customer, house 12)"
            setText(note ?: "")
        }
        container.addView(etLabel)
        container.addView(etPhone)
        container.addView(etNote)

        AlertDialog.Builder(this)
            .setTitle("User profile\n$mac")
            .setView(container)
            .setPositiveButton("Save") { d, _ ->
                val newLabel = etLabel.text.toString().trim().ifEmpty { null }
                val newPhone = etPhone.text.toString().trim().ifEmpty { null }
                val newNote = etNote.text.toString().trim().ifEmpty { null }
                scope.launch {
                    withContext(Dispatchers.IO) {
                        val existing = db.deviceProfileDao().findByMac(mac)
                        db.deviceProfileDao().upsert(
                            (existing ?: DeviceProfile(mac = mac)).copy(
                                label = newLabel,
                                phone = newPhone,
                                note = newNote,
                                lastSeen = System.currentTimeMillis()
                            )
                        )
                    }
                    refreshData(false)
                }
                d.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---------------------------------------------------------------- settings

    private fun wireSettings() {
        etSsid = findViewById(R.id.set_ssid)
        etPass = findViewById(R.id.set_pass)
        etWan = findViewById(R.id.set_wan)
        etLan = findViewById(R.id.set_lan)
        setEnv = findViewById(R.id.set_env)

        apModeGroup = findViewById(R.id.set_ap_mode)
        modeButtons.clear()
        modeButtons[ApMode.AUTO] = R.id.mode_auto
        modeButtons[ApMode.NETSHARE] = R.id.mode_netshare
        modeButtons[ApMode.LOCAL_ONLY] = R.id.mode_localonly
        modeButtons[ApMode.SYSTEM] = R.id.mode_system
        modeButtons[ApMode.ROOT_AP] = R.id.mode_rootap
        modeButtons[ApMode.MANUAL] = R.id.mode_manual

        findViewById<Button>(R.id.btn_detect).setOnClickListener {
            scope.launch {
                val detected = withContext(Dispatchers.IO) {
                    Triple(
                        RootShell.defaultRouteInterface(),
                        SoftApController.apInterface(null),
                        RootShell.interfaces()
                    )
                }
                val (wan, lan, all) = detected
                etWan.setText(wan ?: "")
                etLan.setText(lan ?: "")
                toast(
                    "Internet: ${wan ?: "?"}  ·  Hotspot: ${lan ?: "not detected"}\n" +
                        "Interfaces up: ${all.filter { it.second }.joinToString { it.first }}"
                )
            }
        }

        findViewById<Button>(R.id.btn_apply).setOnClickListener {
            val mode = selectedApMode()
            prefs.edit()
                .putString(HotspotService.KEY_SSID, etSsid.text.toString().trim())
                .putString(HotspotService.KEY_PASS, etPass.text.toString())
                .putString(HotspotService.KEY_WAN_IF, etWan.text.toString().trim())
                .putString(HotspotService.KEY_LAN_IF, etLan.text.toString().trim())
                .putString(HotspotService.KEY_AP_MODE, mode.key)
                .apply()
            AppLog.i(AppLog.TAG_UI, "settings saved - AP mode ${mode.label}, restarting the gateway")
            if (mode != ApMode.SYSTEM && mode != ApMode.MANUAL && !hasWifiSharePermissions()) {
                // Every no-toggle method needs these; asking after saving means the
                // restart that follows can actually succeed.
                requestWifiSharePermissions()
            }
            toast("Saved - restarting with \"${mode.label}\"")
            withService { it.restart() }
        }

        findViewById<Button>(R.id.btn_tether).setOnClickListener { openTetherSettings() }
        findViewById<Button>(R.id.btn_debug_settings).setOnClickListener { openDebugger() }
        findViewById<Button>(R.id.btn_permissions).setOnClickListener {
            if (hasWifiSharePermissions()) {
                toast("Permissions already granted. If an AP still fails, switch Location ON " +
                    "in the system settings, then open the debugger for the reason.")
            } else {
                requestWifiSharePermissions()
            }
        }
        findViewById<Button>(R.id.btn_stop_all).setOnClickListener {
            withService { it.stopSequence() }
        }
        findViewById<Button>(R.id.btn_exit).setOnClickListener {
            confirm(
                "EXIT and clean stop?",
                "Everything is removed: hotspot, DHCP/DNS, NAT, firewall rules, the " +
                    "bandwidth shaper and any leftover from an earlier session. The " +
                    "app closes; press Start/open the app to run the gateway again."
            ) {
                toast("Stopping everything...")
                withService { it.exitAndClean() }
            }
        }
    }

    private fun selectedApMode(): ApMode =
        modeButtons.entries.firstOrNull { it.value == apModeGroup.checkedRadioButtonId }?.key
            ?: ApMode.AUTO

    private fun loadSettingsIntoFields() {
        etSsid.setText(prefs.getString(HotspotService.KEY_SSID, HotspotService.DEFAULT_SSID))
        etPass.setText(prefs.getString(HotspotService.KEY_PASS, HotspotService.DEFAULT_PASS))
        etWan.setText(prefs.getString(HotspotService.KEY_WAN_IF, ""))
        etLan.setText(prefs.getString(HotspotService.KEY_LAN_IF, ""))
        val mode = ApMode.from(prefs.getString(HotspotService.KEY_AP_MODE, ApMode.AUTO.key))
        modeButtons[mode]?.let { apModeGroup.check(it) }
    }

    // ------------------------------------------------------- WiFi-sharing permissions

    /**
     * Location (API 26-32) / NEARBY_WIFI_DEVICES (API 33+) are not optional for
     * NetShare-style sharing: without them the framework refuses to create a
     * local-only hotspot or a WiFi Direct group and reports a generic error.
     */
    private fun hasWifiSharePermissions(): Boolean {
        val needed = wifiSharePermissions()
        return needed.all {
            checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun wifiSharePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                android.Manifest.permission.NEARBY_WIFI_DEVICES,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

    private fun requestWifiSharePermissions() {
        val missing = wifiSharePermissions().filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            toast("All WiFi-sharing permissions are granted")
            return
        }
        AppLog.i(AppLog.TAG_UI, "requesting permissions: ${missing.joinToString()}")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Permissions needed to share WiFi")
            .setMessage(R.string.perm_rationale)
            .setPositiveButton("Ask now") { d, _ ->
                d.dismiss()
                requestPermissions(missing.toTypedArray(), REQ_WIFI_SHARE)
            }
            .setNegativeButton("Later", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_WIFI_SHARE) return
        val granted = permissions.mapIndexed { index, permission ->
            permission.substringAfterLast('.') to
                (grantResults.getOrNull(index) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
        AppLog.i(AppLog.TAG_UI, "permission result: $granted")
        val denied = granted.filterNot { it.second }.map { it.first }
        toast(
            if (denied.isEmpty()) "Permissions granted - retrying the hotspot now"
            else "Denied: ${denied.joinToString()}. The system hotspot still works without them."
        )
        // The first attempt already failed with SecurityException. A grant does
        // not retry by itself; the Hot 8 log sat in WAITING_AP after this.
        if (denied.isEmpty()) withService { it.retryAp() }
    }

    private fun openTetherSettings() {
        scope.launch(Dispatchers.IO) {
            SoftApController.openTetherSettings { }
        }
    }

    // ---------------------------------------------------------------- data + rendering

    private fun refreshData(heavy: Boolean) {
        scope.launch {
            val vouchers = withContext(Dispatchers.IO) { db.voucherDao().getAll() }
            val newProfiles = withContext(Dispatchers.IO) { db.deviceProfileDao().getAll() }
            val sessions = withContext(Dispatchers.IO) { db.sessionDao().getRecent(50) }
            if (heavy) {
                // No shell commands from the UI thread pool any more: the service
                // already collected both (its watchdog tick owns the root shell).
                // Four root commands every ~3 s here is what kept the shell busy -
                // and a busy shell is why START took a minute and STOP took
                // minutes on the 2026-09-24 log.
                val service = svc
                leases = if (service?.state?.phase == HotspotService.Phase.RUNNING) {
                    service.clientsSnapshot()
                } else {
                    emptyList()
                }
                envText = service?.envSnapshot() ?: "(service not connected)"
            }
            allVouchers = vouchers
            profiles = newProfiles
            renderDataViews(sessions)
        }
    }

    private fun renderDataViews(sessions: List<UserSession>) {
        val unused = allVouchers.count { it.status == VoucherStatus.UNUSED }
        val active = allVouchers.count { it.status == VoucherStatus.ACTIVE }
        val expired = allVouchers.count { it.status == VoucherStatus.EXPIRED }
        dashVouchers.text = "$unused unused · $active active · $expired expired"

        val activeVouchers = allVouchers.filter { it.status == VoucherStatus.ACTIVE }
        val withVoucher = leases.count { lease -> activeVouchers.any { it.boundMac == lease.mac } }
        dashClients.text =
            if (leases.isEmpty() && activeVouchers.isEmpty()) "-"
            else "${leases.size} connected · $withVoucher online · ${leases.size - withVoucher} waiting"

        renderVoucherList()

        val rows = leases.map { lease ->
            ClientRow(
                mac = lease.mac,
                ip = lease.ip,
                hostname = lease.hostname,
                label = profiles.firstOrNull { it.mac == lease.mac }?.label,
                voucher = activeVouchers.firstOrNull { it.boundMac == lease.mac }
            )
        }
        clientAdapter.submit(rows)

        profileAdapter.submit(profiles)

        val labels = profiles.associate { profile ->
            profile.mac to (profile.label ?: profile.hostname ?: profile.mac)
        }
        sessionAdapter.submit(sessions, labels)

        setEnv.text = envText.ifBlank { "(not written yet - start the hotspot)" }
    }

    private fun renderVoucherList() {
        voucherAdapter.submit(
            allVouchers.filter { voucherFilter == null || it.status == voucherFilter }
        )
    }

    // ---------------------------------------------------------------- helpers

    private fun withService(block: (HotspotService) -> Unit) {
        val service = svc
        if (service == null) toast("Gateway is still starting - try again in a second")
        else block(service)
    }

    private fun copyText(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("hotspot", text))
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share vouchers"))
    }

    private fun confirm(title: String, message: String, action: () -> Unit) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Yes") { d, _ -> d.dismiss(); action() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    companion object {
        private const val REQ_WIFI_SHARE = 101

        /** Newest events on the dashboard; the debugger keeps the rest. */
        private const val LOG_ON_DASH = 30
    }
}
