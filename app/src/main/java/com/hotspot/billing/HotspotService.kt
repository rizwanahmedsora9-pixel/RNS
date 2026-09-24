package com.hotspot.billing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hotspot.billing.db.AppDatabase
import com.hotspot.billing.db.DeviceProfile
import com.hotspot.billing.db.VoucherStatus
import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.debug.CrashGuard
import com.hotspot.billing.debug.Diagnostics
import com.hotspot.billing.debug.Finding
import com.hotspot.billing.debug.GatewayHealth
import com.hotspot.billing.debug.GatewaySnapshot
import com.hotspot.billing.debug.HealthInput
import com.hotspot.billing.debug.HttpProbe
import com.hotspot.billing.debug.LogFormat
import com.hotspot.billing.debug.LogcatWatcher
import com.hotspot.billing.core.BillingManager
import com.hotspot.billing.core.DeviceManager
import com.hotspot.billing.core.DhcpManager
import com.hotspot.billing.core.DnsManager
import com.hotspot.billing.core.EmergencyCleaner
import com.hotspot.billing.core.FirewallManager
import com.hotspot.billing.core.NatManager
import com.hotspot.billing.core.NetworkController
import com.hotspot.billing.core.UsageMonitor
import com.hotspot.billing.core.WanDetector
import com.hotspot.billing.core.WatchdogManager
import com.hotspot.billing.db.HotspotSession
import com.hotspot.billing.net.ApHandle
import com.hotspot.billing.net.ApLauncher
import com.hotspot.billing.net.ApMode
import com.hotspot.billing.net.IpPool
import com.hotspot.billing.net.LanPlan
import com.hotspot.billing.net.LeaseParser
import com.hotspot.billing.net.SoftApController
import com.hotspot.billing.net.VoucherManager
import com.hotspot.billing.portal.CaptivePortalServer
import com.hotspot.billing.util.RootShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the entire billing gateway: root lifecycle, AP bring-up (including the
 * NetShare-style paths that need no hotspot toggle), NAT/DHCP/firewall, the
 * captive portal, the voucher expiry sweep, the watchdog and the debug log.
 * Runs as a foreground service so swiping the activity away does not kick
 * paying users off the network.
 *
 * Every step is written to [AppLog]: which AP method was tried, what the system
 * answered, which interface appeared, which address was adopted, which firewall
 * rule was applied and what the watchdog found afterwards. The debugger screen
 * turns that into one copyable report.
 */
class HotspotService : android.app.Service() {

    enum class Phase { STARTING, WAITING_AP, RUNNING, STOPPING, STOPPED, ERROR }

    /** Everything the admin UI displays; individual fields are volatile so the
     *  activity can poll them from the main thread safely. */
    class GatewayState {
        @Volatile var phase: Phase = Phase.STOPPED
        @Volatile var rootOk: Boolean? = null
        @Volatile var lanIf: String? = null
        @Volatile var wanIf: String? = null
        @Volatile var portalRunning = false
        @Volatile var onlineClients = 0
        @Volatile var gatewayIp: String? = null
        @Volatile var dhcpOwner: String? = null
        @Volatile var message = ""
        @Volatile var apMode: String? = null
        @Volatile var apKind: String? = null
        @Volatile var apSsid: String? = null
        @Volatile var apPassword: String? = null
        @Volatile var startedAt: String? = null
        @Volatile var findings: List<Finding> = emptyList()
        @Volatile var lastHealthCheck: String? = null

        fun reset() {
            phase = Phase.STOPPED
            rootOk = null
            lanIf = null
            wanIf = null
            portalRunning = false
            onlineClients = 0
            gatewayIp = null
            dhcpOwner = null
            message = ""
            apMode = null
            apKind = null
            apSsid = null
            apPassword = null
            startedAt = null
            findings = emptyList()
            lastHealthCheck = null
        }
    }

    inner class LocalBinder : android.os.Binder() {
        fun service(): HotspotService = this@HotspotService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashGuard.handler())
    private var gatewayJob: Job? = null

    val state = GatewayState()

    private lateinit var db: AppDatabase
    private lateinit var voucherManager: VoucherManager
    private lateinit var prefs: SharedPreferences
    private lateinit var launcher: ApLauncher
    private var portal: CaptivePortalServer? = null
    private var portalGateway: String? = null

    // New core engine (Phase 1-6)
    private lateinit var networkController: NetworkController
    private lateinit var watchdogManager: WatchdogManager
    private lateinit var deviceManager: DeviceManager
    private lateinit var billingManager: BillingManager
    private lateinit var usageMonitor: UsageMonitor
    private var currentApHandle: ApHandle? = null
    private var currentHotspotSessionId: Long? = null
    private lateinit var emergencyCleaner: EmergencyCleaner

    /** Cached for the UI: the phone reads the shell, the activity reads this. */
    @Volatile private var cachedClients: List<LeaseParser.Lease> = emptyList()
    @Volatile private var cachedEnv: String = ""
    @Volatile private var cachedEnvAt = 0L

    /** Codes currently reported by the watchdog, so a finding is logged once. */
    private val reportedFindings = LinkedHashSet<String>()
    private var monitorTicks = 0

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        AppLog.init(this)
        CrashGuard.install(this, appVersionLabel())
        db = AppDatabase.get(this)
        voucherManager = VoucherManager(db)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        launcher = ApLauncher(this)
        // Init new core engine
        networkController = NetworkController(launcher)
        watchdogManager = WatchdogManager()
        deviceManager = DeviceManager(db)
        billingManager = BillingManager(db, voucherManager)
        usageMonitor = UsageMonitor(db)
        emergencyCleaner = EmergencyCleaner(
            stopPortal = { portal?.stop(); portal = null },
            releaseAp = {
                currentApHandle?.close { log(it) }
                currentApHandle = null
                launcher.release { log(it) }
                SoftApController.stopAp { log(it) }
            },
            log = { log(it) }
        )
        createChannel()
        registerStateReceivers()
        startInForeground()
        log("service created (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}, ${Build.MODEL})")
        log("core engine: NetworkController + WanDetector + DhcpManager + NatManager + DnsManager + FirewallManager + WatchdogManager + DeviceManager + BillingManager + UsageMonitor")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSequence()
            ACTION_DIAGNOSE -> scope.launch { runDiagnostics("requested from the notification") }
            else -> startSequence()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        log("service destroyed")
        gatewayJob?.cancel()
        try {
            unregisterReceiver(stateReceiver)
        } catch (e: Exception) {
            // never registered / already gone
        }
        LogcatWatcher.stop()
        // Best-effort synchronous cleanup; usually already torn down by stopSequence().
        try { portal?.stop() } catch (e: Exception) { /* socket already gone */ }
        try { RootShell.stopBandwidth() } catch (e: Exception) { /* nothing to stop */ }
        try { RootShell.stopNetwork() } catch (e: Exception) { /* nothing to stop */ }
        portal = null
        scope.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- public API (via binder)

    /** Start (or keep) the gateway. Safe to call repeatedly. */
    fun startSequence() {
        if (gatewayJob?.isActive == true) return
        gatewayJob = scope.launch { runGateway() }
    }

    /** Tear everything down but keep the (silent) service alive. */
    fun stopSequence() {
        // Immediate feedback: the button says STOPPING the moment it is pressed,
        // the slow part happens in the background.
        if (state.phase != Phase.STOPPED) {
            state.phase = Phase.STOPPING
            state.message = "Stopping..."
            updateNotification()
        }
        gatewayJob?.cancel()
        gatewayJob = scope.launch { teardown() }
    }

    /**
     * EXIT & clean stop: tear everything down, then close the service and its
     * notification. Anything the previous crash may have left behind (an
     * untracked dnsmasq, a firewall chain, a tc class, the P2P group) is removed
     * by [EmergencyCleaner], not just the parts this process knows about.
     */
    fun exitAndClean() {
        if (state.phase != Phase.STOPPED) {
            state.phase = Phase.STOPPING
            state.message = "Stopping everything..."
            updateNotification()
        }
        gatewayJob?.cancel()
        gatewayJob = scope.launch {
            teardown()
            try {
                stopForeground(true)
            } catch (e: Throwable) {
                AppLog.w(AppLog.TAG_SERVICE, "could not drop the notification: ${e.message}")
            }
            log("EXIT: everything is off, the service is closing")
            stopSelf()
        }
    }

    /** Apply new settings: full stop, then start again. */
    fun restart() {
        gatewayJob?.cancel()
        gatewayJob = scope.launch {
            teardown()
            runGateway()
        }
    }

    /** The dashboard's event log: newest last, compact timestamps. */
    fun dumpLog(): List<String> =
        AppLog.snapshot(limit = MAX_LOG_LINES, min = AppLog.minLevel())
            .map { LogFormat.shortLine(it) }

    /** What the gateway looks like right now, for the diagnostic report. */
    fun snapshot(): GatewaySnapshot = GatewaySnapshot(
        phase = state.phase.name,
        rootOk = state.rootOk,
        lanIf = state.lanIf,
        wanIf = state.wanIf,
        gatewayIp = state.gatewayIp,
        dhcpOwner = state.dhcpOwner,
        portalRunning = state.portalRunning,
        onlineClients = state.onlineClients,
        message = state.message,
        apKind = state.apKind,
        apSsid = state.apSsid,
        apPassword = state.apPassword,
        apMode = state.apMode,
        startedAt = state.startedAt
    )

    fun findings(): List<Finding> = state.findings

    /**
     * Builds the full copyable report. Blocking - callers must be on IO.
     * Returns the report text; also logged (truncated) so the debugger shows it
     * was produced.
     */
    fun buildReport(): String {
        val report = Diagnostics.collect(
            context = this,
            snapshot = snapshot(),
            findings = state.findings,
            voucherSummary = voucherSummary()
        )
        AppLog.i(
            AppLog.TAG_SERVICE,
            "diagnostic report built (${report.length} chars) - copy or share it from the debugger"
        )
        return report
    }

    /** Runs a fresh health check on demand (the debugger's "Check now" button). */
    suspend fun checkNow(): List<Finding> = withContext(Dispatchers.IO) { healthCheck(report = true) }

    /** Re-runs the whole AP strategy on demand ("Try NetShare again"). */
    fun retryAp() {
        log("AP retry requested from the UI")
        gatewayJob?.cancel()
        gatewayJob = scope.launch { runGateway() }
    }

    private suspend fun runDiagnostics(reason: String): String = withContext(Dispatchers.IO) {
        log("collecting a full diagnostic report ($reason)")
        healthCheck(report = true)
        buildReport()
    }

    private fun voucherSummary(): String {
        return try {
            val all = db.voucherDao().getAll()
            val byStatus = all.groupingBy { it.status }.eachCount()
            buildString {
                append("total: ").append(all.size).append('\n')
                for (status in VoucherStatus.values()) {
                    append(status.name.lowercase()).append(": ")
                        .append(byStatus[status] ?: 0).append('\n')
                }
                append("-- active --\n")
                val active = all.filter { it.status == VoucherStatus.ACTIVE }
                if (active.isEmpty()) append("(none)\n")
                for (v in active.take(40)) {
                    append(v.code).append(' ')
                        .append(v.planName).append(" mac=").append(v.boundMac ?: "?")
                        .append(" ip=").append(v.assignedIp ?: "?")
                        .append(" class=").append(v.classId ?: "?")
                        .append(" expires=").append(
                            v.expiresAt?.let { LogFormat.timestamp(it) } ?: "?"
                        ).append('\n')
                }
                if (active.size > 40) append("... and ${active.size - 40} more\n")
            }
        } catch (e: Throwable) {
            "(could not read the voucher database: ${e.message})"
        }
    }

    // ---------------------------------------------------------------- gateway logic

    private suspend fun runGateway() {
        state.reset()
        state.phase = Phase.STARTING
        state.message = "Starting..."
        state.startedAt = LogFormat.timestamp(System.currentTimeMillis())
        updateNotification()

        if (!ensureRoot()) return

        log("deploying network scripts")
        deployScripts()
        startLogcatWatcher()

        // "Restart app -> old sessions cleaned" (master plan TEST 7). A dnsmasq,
        // a firewall chain or a tc class left by a previous process would fight
        // the new session: the 2026-09-24 log's "Address already in use" came
        // from exactly that. Cost: one root command.
        emergencyCleaner.cleanupEverything("starting a new session")

        // Ensure default voucher plans exist (Phase 8)
        try {
            billingManager.ensureDefaultPlans()
        } catch (e: Throwable) {
            AppLog.w(AppLog.TAG_BILLING, "billing: ensureDefaultPlans failed ${e.message}")
        }

        val mode = ApMode.from(prefs.getString(KEY_AP_MODE, ApMode.AUTO.key))
        val ssid = prefs.getString(KEY_SSID, DEFAULT_SSID) ?: DEFAULT_SSID
        val pass = prefs.getString(KEY_PASS, DEFAULT_PASS) ?: DEFAULT_PASS
        val pin = prefs.getString(KEY_LAN_IF, null)?.trim()?.takeIf { it.isNotEmpty() }
        state.apMode = mode.label

        // --- New Engine: NetworkController START flow ---
        // Detect WAN, create LAN, start DHCP/DNS/NAT, test internet
        val startResult = withContext(Dispatchers.IO) {
            networkController.start(mode, ssid, pass, pin) { log(it) }
        }

        when (startResult) {
            is NetworkController.StartResult.Failed -> {
                // If NetworkController failed at WAN or LAN creation, fallback to old waiting logic
                if (startResult.step == NetworkController.Step.WAN_DETECTION ||
                    startResult.step == NetworkController.Step.LAN_CREATION) {
                    log("network: NetworkController failed at ${startResult.step}: ${startResult.reason}, trying fallback waiting")
                    state.phase = Phase.WAITING_AP
                    state.message = waitingMessage(mode) + " Reason: ${startResult.reason}"
                    updateNotification()

                    val discovered = waitForApOrInterface(mode, ssid, pass, pin) ?: return
                    applyHandle(launcher.current())
                    val lan = discovered
                    withContext(Dispatchers.IO) { launcher.waitForAddress(lan, ADDRESS_WAIT_MS) { log(it) } }
                    if (!configureGateway(lan)) return
                } else {
                    state.phase = Phase.ERROR
                    state.message = "Failed at ${startResult.step}: ${startResult.reason}"
                    log("network: START failed at ${startResult.step}: ${startResult.reason}")
                    updateNotification()
                    return
                }
            }
            is NetworkController.StartResult.Success -> {
                currentApHandle = startResult.apHandle
                applyHandle(startResult.apHandle)
                state.lanIf = startResult.lanIf
                state.wanIf = startResult.wanInfo.interfaceName
                state.gatewayIp = startResult.lanPlan?.gateway ?: startResult.dhcpConfig.gateway
                state.dhcpOwner = startResult.lanPlan?.dhcpOwner ?: "ours"
                IpPool.configure(startResult.lanPlan ?: LanPlan.DEFAULT)

                // Init bandwidth
                val shaper = RootShell.initBandwidth()
                if (!shaper.isSuccess) {
                    log("shaper init warning: ${shaper.err.firstOrNull()?.trim() ?: "unknown"}")
                }
                voucherManager.reapplyAll()
                ensurePortal(state.gatewayIp ?: startResult.dhcpConfig.gateway)

                // Record hotspot session (Phase 7)
                try {
                    val session = HotspotSession(
                        mode = mode.key,
                        status = "RUNNING",
                        wanIf = state.wanIf,
                        lanIf = state.lanIf,
                        ssid = state.apSsid
                    )
                    currentHotspotSessionId = withContext(Dispatchers.IO) {
                        db.hotspotSessionDao().insert(session)
                    }
                    log("hotspot session recorded id=$currentHotspotSessionId")
                } catch (e: Throwable) {
                    AppLog.w(AppLog.TAG_SERVICE, "session record failed ${e.message}")
                }

                state.phase = Phase.RUNNING
                state.message = "Running on ${startResult.lanIf} via ${startResult.apHandle?.kind?.label ?: "unknown"} · http://${state.gatewayIp}/ · ${startResult.wanInfo.type} ${startResult.wanInfo.interfaceName}"
                log("gateway running via NetworkController: WAN=${startResult.wanInfo.interfaceName} LAN=${startResult.lanIf} gateway=${state.gatewayIp}")
                updateNotification()
            }
        }

        // Watchdog loop with granular heal
        while (true) {
            delay(MONITOR_INTERVAL_MS)
            if (!monitorOnce()) return
        }
    }

    private fun applyHandle(handle: ApHandle?) {
        state.apKind = handle?.kind?.label
        state.apSsid = handle?.ssid
        state.apPassword = handle?.password
        if (handle != null) {
            log("network: ${handle.joinInstructions()}")
        }
    }

    private fun waitingMessage(mode: ApMode): String = when (mode) {
        ApMode.MANUAL, ApMode.SYSTEM ->
            "Hotspot is off. Switch it on from quick settings or press " +
                "\u201cOpen Android hotspot settings\u201d - the gateway takes over automatically."
        else ->
            "No WiFi network could be created yet (see the debugger for the reason). " +
                "Retrying every ${RETRY_INTERVAL_MS / 1000}s. Switch the Android hotspot on " +
                "and this gateway takes over immediately."
    }

    /**
     * Waits for any AP interface to appear, re-running the chosen strategy every
     * [RETRY_INTERVAL_MS] so a later fix (Location switched on, WiFi reconnected,
     * driver ready) is picked up without a restart.
     */
    private suspend fun waitForApOrInterface(
        mode: ApMode,
        ssid: String,
        pass: String,
        pin: String?
    ): String? {
        var attempt = 0
        val started = SystemClock.elapsedRealtime()
        while (true) {
            SoftApController.apInterface(pin ?: state.lanIf)?.let { return it }
            if (SystemClock.elapsedRealtime() - started > RETRY_INTERVAL_MS) {
                attempt++
                log("ap: retry #$attempt - trying \"${mode.label}\" again")
                val handle = withContext(Dispatchers.IO) {
                    launcher.launch(mode, ssid, pass, pin) { log(it) }
                }
                applyHandle(handle)
                handle?.interfaceName?.let { return it }
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * One pass of the watchdog. Returns false when the gateway should stop.
     *
     * Cost per tick is now: ONE root command (the probe, cached for 2 s) plus the
     * work that is actually needed. The previous version asked the shell ~15
     * times per tick and called `setup_network.sh keepalive` on every pass - that
     * command reports 20 s on the device, so an 8 s tick could never keep up and
     * the root shell was permanently busy. Keepalive is now a repair, not a
     * heartbeat: it runs only when the probe says something is missing.
     */
    private suspend fun monitorOnce(): Boolean {
        monitorTicks++

        // Someone else is holding the root shell (a start, a stop, a voucher
        // redemption): do not pile more commands on top of it, and do not report
        // findings from a half-applied state either.
        if (RootShell.isBusy()) {
            AppLog.i(AppLog.TAG_WATCHDOG, "root shell busy - skipping this watchdog tick")
            return true
        }

        val probe = RootShell.probe(state.lanIf)

        // If the probe cannot answer (script missing, shell unavailable), fall
        // back to the interface check we have always used.
        val lan = when {
            probe != null -> if (probe.lanUp == true) (probe.lanIf ?: state.lanIf) else null
            else -> SoftApController.apInterface(state.lanIf)
        }

        if (lan != null && state.lanIf == null) {
            state.lanIf = lan
        }

        val watchdogResult = withContext(Dispatchers.IO) {
            watchdogManager.check(state.lanIf)
        }

        if (watchdogResult.issues.isNotEmpty()) {
            log("watchdog: ${watchdogResult.issues.joinToString()}")
            val healed = withContext(Dispatchers.IO) {
                watchdogManager.heal(watchdogResult) { log(it) }
            }
            if (!healed) {
                if (!watchdogResult.apAlive) {
                    log("watchdog: LAN interface ${state.lanIf ?: "?"} disappeared")
                    if (launcher.current()?.interfaceName == state.lanIf) launcher.noteSystemTookTheAp { log(it) }
                    state.phase = Phase.WAITING_AP
                    state.message = "Hotspot went off - switch it back on, the gateway re-arms itself."
                    updateNotification()
                    val mode = ApMode.from(prefs.getString(KEY_AP_MODE, ApMode.AUTO.key))
                    val ssid = prefs.getString(KEY_SSID, DEFAULT_SSID) ?: DEFAULT_SSID
                    val pass = prefs.getString(KEY_PASS, DEFAULT_PASS) ?: DEFAULT_PASS
                    val pin = prefs.getString(KEY_LAN_IF, null)?.trim()?.takeIf { it.isNotEmpty() }
                    val back = waitForApOrInterface(mode, ssid, pass, pin) ?: return false
                    applyHandle(launcher.current())
                    withContext(Dispatchers.IO) { launcher.waitForAddress(back, ADDRESS_WAIT_MS) { log(it) } }
                    if (!configureGateway(back)) return false
                    return true
                }
            }
        }

        if (lan == null) {
            log("watchdog: LAN interface ${state.lanIf ?: "?"} disappeared")
            if (launcher.current()?.interfaceName == state.lanIf) launcher.noteSystemTookTheAp { log(it) }
            state.phase = Phase.WAITING_AP
            state.message = "Hotspot went off - switch it back on, the gateway re-arms itself."
            updateNotification()
            val mode = ApMode.from(prefs.getString(KEY_AP_MODE, ApMode.AUTO.key))
            val ssid = prefs.getString(KEY_SSID, DEFAULT_SSID) ?: DEFAULT_SSID
            val pass = prefs.getString(KEY_PASS, DEFAULT_PASS) ?: DEFAULT_PASS
            val pin = prefs.getString(KEY_LAN_IF, null)?.trim()?.takeIf { it.isNotEmpty() }
            val back = waitForApOrInterface(mode, ssid, pass, pin) ?: return false
            applyHandle(launcher.current())
            withContext(Dispatchers.IO) { launcher.waitForAddress(back, ADDRESS_WAIT_MS) { log(it) } }
            if (!configureGateway(back)) return false
            return true
        }

        // The address is reported by the probe; only when it is genuinely gone do
        // we re-run the gateway configuration.
        if (probe != null && probe.lanAddress.isNullOrBlank()) {
            log("hotspot address is missing on $lan - re-applying the gateway configuration")
            if (!configureGateway(lan)) return false
            return true
        }
        probe?.gateway?.let { state.gatewayIp = it }
        state.wanIf = probe?.wanIf ?: state.wanIf
        DhcpManager.owner()?.let { state.dhcpOwner = it }

        voucherManager.sweepExpired()
        refreshStats()

        // Usage monitoring every tick (Phase 11). It reads the same cached leases
        // and one iptables counter dump.
        try {
            val usageStats = usageMonitor.collectUsage(lan)
            usageMonitor.updateDatabase(usageStats)
            // Check data limits every 3 ticks to avoid heavy DB work
            if (monitorTicks % 3 == 0) {
                usageMonitor.checkAndEnforceLimits(billingManager)
            }
        } catch (e: Throwable) {
            AppLog.w(AppLog.TAG_NET, "usage monitor failed ${e.message}")
        }

        // The deep health check is expensive (an HTTP probe), so it runs every
        // DEEP_CHECK_EVERY_TICKS passes, not every one.
        if (monitorTicks % DEEP_CHECK_EVERY_TICKS == 0) {
            healthCheck(report = false)
        }
        updateNotification()
        return true
    }

    /**
     * Collects the watchdog's view of the gateway and turns it into findings.
     * New findings are logged once (with their H-code); recovered ones are logged
     * once as well, so the log reads as a story instead of a broken record.
     */
    private suspend fun healthCheck(report: Boolean): List<Finding> = withContext(Dispatchers.IO) {
        val lan = state.lanIf

        // One command for everything the health rules look at (interfaces,
        // address, dnsmasq ownership, jumps, masquerade, portal redirect,
        // policy routing, lease/authorized counts).
        val rp = RootShell.probe(lan, fresh = report)
        val plan = RootShell.readLanPlan()
        val lanExists = rp?.lanUp ?: (lan != null && (try {
            RootShell.interfaces().any { it.first == lan }
        } catch (e: Throwable) {
            false
        }))
        val addresses = rp?.lanAddress?.let { listOf(it) }
            ?: if (lan != null && lanExists) {
                try { RootShell.lanAddresses(lan) } catch (e: Throwable) { emptyList() }
            } else {
                emptyList()
            }
        // The HTTP probe is the only expensive check left, and only the debugger
        // and the deep tick pay for it.
        val portalProbe = if (report || state.phase == Phase.RUNNING) {
            val gw = rp?.gateway ?: plan?.gateway ?: state.gatewayIp
            if (gw != null && state.portalRunning) {
                HttpProbe.get("http://$gw/generate_204", 3_000).status
            } else {
                null
            }
        } else {
            null
        }
        val authorized = rp?.authorized ?: try {
            RootShell.run("cat $AUTHORIZED_FILE 2>/dev/null; true", quiet = true)
                .out.count { it.isNotBlank() }
        } catch (e: Throwable) {
            0
        }

        val input = HealthInput(
            lanIf = lan,
            lanInterfaceExists = lanExists,
            lanAddresses = addresses,
            expectedGateway = rp?.gateway ?: plan?.gateway,
            ipForwardEnabled = rp?.ipForward
                ?: (try { RootShell.ipForwardEnabled() } catch (e: Throwable) { null }),
            ourDnsmasqRunning = rp?.dhcpOurs == true || rp?.dhcpOrphan == true ||
                (rp == null && (try { RootShell.isDnsmasqRunning() } catch (e: Throwable) { false })),
            foreignDnsmasqRunning = rp?.dhcpForeign
                ?: (try { RootShell.isForeignDnsmasqRunning() } catch (e: Throwable) { false }),
            dhcpOwner = DhcpManager.owner() ?: plan?.dhcpOwner ?: state.dhcpOwner,
            portalAlive = portal?.isAlive == true,
            portalProbeStatus = portalProbe,
            natJumpFirst = rp?.natJump
                ?: (try { RootShell.jumpIsFirst("nat", "PREROUTING", "HS_NAT") } catch (e: Throwable) { null }),
            forwardJumpFirst = rp?.forwardJump
                ?: (try { RootShell.jumpIsFirst("filter", "FORWARD", "HS_FWD") } catch (e: Throwable) { null }),
            wanIf = state.wanIf,
            defaultRouteIf = rp?.wanIf
                ?: (try { RootShell.defaultRouteInterface() } catch (e: Throwable) { null }),
            policyRoutingOk = rp?.let { it.ruleIif == true && it.ruleSubnet == true },
            connectedClients = state.onlineClients,
            authorizedClients = authorized,
            apKind = state.apKind
        )

        val findings = GatewayHealth.evaluate(input)
        state.findings = findings
        state.lastHealthCheck = LogFormat.clock(System.currentTimeMillis())

        val codes = findings.map { "${it.code}:${it.problem.hashCode()}" }.toSet()
        for (finding in findings) {
            val key = "${finding.code}:${finding.problem.hashCode()}"
            if (reportedFindings.add(key)) {
                AppLog.log(finding.level, AppLog.TAG_WATCHDOG, finding.format())
            }
        }
        val gone = reportedFindings.filter { it !in codes }
        for (key in gone) {
            reportedFindings.remove(key)
            AppLog.i(AppLog.TAG_WATCHDOG, "${key.substringBefore(':')} recovered")
        }

        val headline = GatewayHealth.headline(findings)
        if (headline != null && state.phase == Phase.RUNNING) {
            state.message = headline
        }
        if (report) {
            log(
                "health check: ${if (findings.isEmpty()) "no problems found"
                else findings.joinToString { it.code }} " +
                    "(probe ${rp?.tookMs ?: -1}ms, dnsmasq ours=${input.ourDnsmasqRunning} android=${input.foreignDnsmasqRunning}, " +
                    "owner=${input.dhcpOwner}, forward=${input.ipForwardEnabled}, " +
                    "routing=${input.policyRoutingOk}, portal=${input.portalAlive}/probe=${input.portalProbeStatus})"
            )
        }
        findings
    }

    private suspend fun ensureRoot(): Boolean = withContext(Dispatchers.IO) {
        val ok = try {
            RootShell.isRootAvailable()
        } catch (e: Exception) {
            AppLog.e(AppLog.TAG_ROOT, "root check failed", e)
            false
        }
        state.rootOk = ok
        if (!ok) {
            state.phase = Phase.ERROR
            state.message = "Root not granted. Allow this app in Magisk, then press Start."
        }
        log(if (ok) "root granted" else "root NOT granted - open Magisk and allow this app")
        updateNotification()
        ok
    }

    private suspend fun configureGateway(lanIf: String): Boolean = withContext(Dispatchers.IO) {
        state.lanIf = lanIf
        state.wanIf = RootShell.defaultRouteInterface()
        writeEnvFromPrefs()

        val leaveDhcp = launcher.shouldLeaveAndroidDhcp(lanIf)
        if (leaveDhcp) log("Android owns DHCP on $lanIf - leaving its dnsmasq running")
        DhcpManager.noteLeaveAndroidDhcp(leaveDhcp)
        val res = RootShell.startNetwork(leaveDhcp)
        res.out.forEach { if (it.isNotBlank()) log(it) }
        if (!res.isSuccess) {
            val err = res.err.firstOrNull { it.isNotBlank() }?.trim() ?: "unknown error"
            state.phase = Phase.ERROR
            state.message = "Network setup failed: $err"
            log("setup_network start FAILED: $err")
            updateNotification()
            return@withContext false
        }

        val plan = RootShell.readLanPlan() ?: LanPlan.DEFAULT
        state.gatewayIp = plan.gateway
        state.dhcpOwner = plan.dhcpOwner
        IpPool.configure(plan)

        val shaper = RootShell.initBandwidth()
        if (!shaper.isSuccess) {
            log("shaper init warning: ${shaper.err.firstOrNull()?.trim() ?: "unknown"}")
        }

        voucherManager.reapplyAll()
        ensurePortal(plan.gateway)

        state.phase = Phase.RUNNING
        state.message = runningMessage(lanIf, plan)
        log("gateway running on $lanIf (WAN ${state.wanIf ?: "unknown"}, ${plan.gateway}, dhcp ${plan.dhcpOwner})")
        updateNotification()
        true
    }

    private fun ensurePortal(gateway: String) {
        if (portal != null && portalGateway != gateway) {
            try { portal?.stop() } catch (e: Exception) { /* rebound onto the new gateway */ }
            portal = null
        }
        if (portal == null) {
            portal = CaptivePortalServer(voucherManager, gateway) { AppLog.i(AppLog.TAG_PORTAL, it) }
            try {
                portal?.start()
                log("captive portal listening on 0.0.0.0:${CaptivePortalServer.PORT} (gateway $gateway)")
            } catch (e: Exception) {
                log("captive portal failed to start: ${e.message}")
                AppLog.e(AppLog.TAG_PORTAL, "portal start failed", e)
            }
            portalGateway = gateway
        }
        state.portalRunning = portal?.isAlive ?: false
    }

    private fun runningMessage(lanIf: String, plan: LanPlan): String {
        val dhcp = when (plan.dhcpOwner) {
            "android" -> "phone DHCP"
            "ours-dns" -> "phone DHCP + app DNS"
            "failed" -> "DHCP DOWN"
            else -> "app DHCP"
        }
        val via = state.apKind?.let { " via $it" } ?: ""
        return "Running on $lanIf$via · http://${plan.gateway}/ · $dhcp. " +
            "Stuck on Obtaining IP? Forget the Wi-Fi and rejoin."
    }

    private suspend fun teardown() = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        log("stopping gateway")

        // One call that stops the portal, removes every rule/process/state file of
        // ours (including leftovers from an earlier session) and releases the AP -
        // the rule removal and the AP release run at the same time.
        val clean = emergencyCleaner.cleanupEverything("Stop pressed", state.lanIf)

        currentHotspotSessionId?.let { id ->
            try {
                db.hotspotSessionDao().close(id, System.currentTimeMillis(), "STOPPED", 0)
                log("hotspot session $id closed")
            } catch (e: Throwable) {
                AppLog.w(AppLog.TAG_SERVICE, "session close failed ${e.message}")
            }
            currentHotspotSessionId = null
        }

        if (!clean) {
            log("gateway stopped with leftovers - see the warnings above (${System.currentTimeMillis() - started}ms)")
        } else {
            log("gateway stopped cleanly in ${System.currentTimeMillis() - started}ms")
        }

        launcher.release { log(it) }
        RootShell.invalidateCaches()
        state.reset()
        state.phase = Phase.STOPPED
        state.message = "Stopped"
        reportedFindings.clear()
        updateNotification()
    }

    private fun writeEnvFromPrefs() {
        val env = mutableMapOf<String, String>()
        state.lanIf?.let { env["LAN_IF"] = it }
        prefs.getString(KEY_WAN_IF, null)?.trim()?.takeIf { it.isNotEmpty() }?.let { env["WAN_IF"] = it }
        try {
            RootShell.writeEnvFile(env)
            log("hotspot.env: " + env.entries.joinToString { "${it.key}=${it.value}" })
        } catch (e: IllegalArgumentException) {
            log("hotspot.env NOT written - invalid interface name: ${e.message}")
        }
    }

    private fun deployScripts() {
        for (name in SCRIPTS) {
            try {
                val tmpLocal = java.io.File(cacheDir, name)
                assets.open(name).use { input ->
                    tmpLocal.outputStream().use { output -> input.copyTo(output) }
                }
                val res = RootShell.run(
                    "cp ${tmpLocal.absolutePath} $SCRIPT_DIR/$name && chmod 755 $SCRIPT_DIR/$name"
                )
                if (!res.isSuccess) log("could not deploy $name: ${res.err.joinToString()}")
            } catch (e: Throwable) {
                log("could not deploy $name: ${e.javaClass.simpleName}: ${e.message}")
                AppLog.e(AppLog.TAG_SERVICE, "deploying $name failed", e)
            }
        }
    }

    /** Client count + auto-recording of every device seen on the LAN. */
    private fun refreshStats() {
        // Cached reads: the lease file and ARP are also polled by the UI and by the
        // voucher sweep, and four processes used to run the same `cat` within the
        // same second (visible as parallel `cat /proc/net/arp` lines in the
        // 2026-09-24 debug log).
        val leases = RootShell.connectedClients(state.lanIf)
        cachedClients = leases
        state.onlineClients = leases.size
        val dao = db.deviceProfileDao()
        val now = System.currentTimeMillis()
        for (lease in leases) {
            val existing = dao.findByMac(lease.mac)
            if (existing == null) {
                dao.upsert(DeviceProfile(mac = lease.mac, hostname = lease.hostname))
                log("new device on the LAN: ${lease.mac} ${lease.hostname.ifBlank { "(no hostname)" }}")
            } else if (existing.hostname != lease.hostname || now - existing.lastSeen > DEVICE_SEEN_UPDATE_MS) {
                val hostname = lease.hostname.ifBlank { existing.hostname }
                dao.upsert(existing.copy(hostname = hostname, lastSeen = now))
            }
        }
    }

    // ---------------------------------------------------------------- read-only views for the UI

    /**
     * Devices on the LAN, last collected by the watchdog. The activity polls this
     * from the main thread, so it must not touch the shell: the UI used to run
     * four root commands every 3 seconds, which is a large part of why the root
     * shell was permanently busy in the 2026-09-24 log.
     */
    fun clientsSnapshot(): List<LeaseParser.Lease> = cachedClients

    /** hotspot.env + hotspot.runtime, re-read at most every 10 s. */
    suspend fun envSnapshot(): String {
        val age = System.currentTimeMillis() - cachedEnvAt
        if (cachedEnv.isNotBlank() && age < ENV_TTL_MS) return cachedEnv
        return withContext(Dispatchers.IO) {
            val text = try {
                val env = RootShell.readEnvFile()
                val runtime = RootShell.readRuntimeFile().ifBlank { "(runtime not written yet)" }
                "$env\n---\n$runtime"
            } catch (e: Throwable) {
                "(could not read the state files: ${e.message})"
            }
            cachedEnv = text
            cachedEnvAt = System.currentTimeMillis()
            text
        }
    }

    // ---------------------------------------------------------------- system state receivers

    /**
     * Logs what the system does to WiFi/the AP while we run, and re-arms the
     * gateway when a hotspot interface appears or disappears. Everything here is
     * diagnostic first: a state change that we only learn about from logcat is a
     * state change we cannot react to.
     */
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            when (action) {
                AP_STATE_ACTION -> {
                    val extra = intent.getIntExtra(EXTRA_AP_STATE, -1)
                    log("system broadcast: hotspot state changed -> ${describeApState(extra)}")
                }
                android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    val extra = intent.getIntExtra(android.net.wifi.WifiManager.EXTRA_WIFI_STATE, -1)
                    log("system broadcast: WiFi state -> ${describeWifiState(extra)}")
                }
                android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val info = intent.getParcelableExtra<NetworkInfo>(
                        android.net.wifi.WifiManager.EXTRA_NETWORK_INFO
                    )
                    log("system broadcast: WiFi network state -> ${info?.state} ${info?.extraInfo ?: ""}")
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val info = intent.getParcelableExtra<NetworkInfo>(
                        WifiP2pManager.EXTRA_NETWORK_INFO
                    )
                    log("system broadcast: WiFi Direct -> ${info?.state} connected=${info?.isConnected}")
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val extra = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    log(
                        "system broadcast: WiFi Direct ${if (extra == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                            "enabled" else "disabled ($extra)"}"
                    )
                }
                ConnectivityManager.CONNECTIVITY_ACTION -> {
                    @Suppress("DEPRECATION")
                    val info = intent.getParcelableExtra<NetworkInfo>(
                        ConnectivityManager.EXTRA_NETWORK_INFO
                    )
                    log("system broadcast: connectivity -> ${info?.typeName} ${info?.state}")
                    // The internet side may have moved (WiFi <-> mobile data).
                    if (state.phase == Phase.RUNNING) {
                        val wan = try { RootShell.defaultRouteInterface() } catch (e: Throwable) { null }
                        if (wan != null && wan != state.wanIf) {
                            log("internet side changed to $wan - re-applying NAT")
                            state.lanIf?.let { lan -> scope.launch { configureGateway(lan) } }
                        }
                    }
                }
                else -> log("system broadcast: $action")
            }
        }
    }

    private fun registerStateReceivers() {
        val filter = IntentFilter().apply {
            addAction(AP_STATE_ACTION)
            addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            @Suppress("DEPRECATION")
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        try {
            ContextCompat.registerReceiver(
                this, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Throwable) {
            log("could not register the state receiver: ${e.message}")
            try {
                @Suppress("DEPRECATION")
                registerReceiver(stateReceiver, filter)
            } catch (e2: Throwable) {
                AppLog.e(AppLog.TAG_SERVICE, "state receiver registration failed", e2)
            }
        }
    }

    // ---------------------------------------------------------------- logging + notification

    /** The one place the gateway writes human-readable events. */
    private fun log(line: String) {
        AppLog.i(AppLog.TAG_SERVICE, line)
    }

    private fun startLogcatWatcher() {
        val mode = LogcatWatcher.Mode.from(prefs.getString(KEY_LOGCAT_MODE, LogcatWatcher.Mode.FILTERED.key))
        val root = state.rootOk == true
        if (mode == LogcatWatcher.Mode.OFF) {
            LogcatWatcher.stop()
            return
        }
        LogcatWatcher.start(mode, root)
        log("logcat watcher: ${mode.label}" + (if (root) " (rooted - full system log)" else " (no root - this app only)"))
    }

    /** Called by the debugger UI when the operator changes the watcher mode. */
    fun applyLogcatMode(mode: LogcatWatcher.Mode) {
        prefs.edit().putString(KEY_LOGCAT_MODE, mode.key).apply()
        startLogcatWatcher()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Hotspot status", NotificationManager.IMPORTANCE_LOW
        )
        channel.description = "Gateway running state"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun startInForeground() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID, buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun updateNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification())
        } catch (e: Throwable) {
            AppLog.w(AppLog.TAG_SERVICE, "could not update the notification: ${e.message}")
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, HotspotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val debugIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, DebugActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val text = state.message.ifBlank { state.phase.name.lowercase() }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("RNS Hotspot")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Debugger", debugIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    private fun appVersionLabel(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        "${info.versionName} (${info.packageName})"
    } catch (e: Exception) {
        "unknown version"
    }

    private fun describeApState(raw: Int): String = when (raw) {
        0 -> "DISABLING"
        1 -> "DISABLED"
        2 -> "ENABLING"
        3 -> "ENABLED"
        4 -> "FAILED"
        else -> "unknown ($raw)"
    }

    private fun describeWifiState(raw: Int): String = when (raw) {
        0 -> "DISABLING"
        1 -> "DISABLED"
        2 -> "ENABLING"
        3 -> "ENABLED"
        4 -> "UNKNOWN"
        else -> "unknown ($raw)"
    }

    companion object {
        const val ACTION_START = "com.hotspot.billing.START"
        const val ACTION_STOP = "com.hotspot.billing.STOP"
        const val ACTION_DIAGNOSE = "com.hotspot.billing.DIAGNOSE"

        const val PREFS = "hotspot_prefs"
        const val KEY_SSID = "ssid"
        const val KEY_PASS = "pass"
        const val KEY_WAN_IF = "wan_if"
        const val KEY_LAN_IF = "lan_if"
        const val KEY_AP_MODE = "ap_mode"
        const val KEY_LOGCAT_MODE = "logcat_mode"
        const val DEFAULT_SSID = "RNS-Hotspot"
        const val DEFAULT_PASS = "hotspot123"

        /** Not in the SDK: the string itself has been stable since Android 2. */
        private const val AP_STATE_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val EXTRA_AP_STATE = "wifi_state"

        private const val SCRIPT_DIR = "/data/local/tmp"
        private const val AUTHORIZED_FILE = "/data/local/tmp/authorized_macs.txt"
        private const val ENV_TTL_MS = 10_000L
        private val SCRIPTS = listOf("setup_network.sh", "bandwidth_control.sh", "netshare_ap.sh")
        private const val MONITOR_INTERVAL_MS = 8_000L
        private const val POLL_INTERVAL_MS = 2_000L
        private const val AP_WAIT_MS = 15_000L
        private const val ADDRESS_WAIT_MS = 12_000L
        private const val RETRY_INTERVAL_MS = 20_000L
        private const val DEEP_CHECK_EVERY_TICKS = 8
        private const val MAX_LOG_LINES = 400
        private const val DEVICE_SEEN_UPDATE_MS = 5 * 60_000L
        private const val CHANNEL_ID = "hotspot_status"
        private const val NOTIF_ID = 42
    }
}
