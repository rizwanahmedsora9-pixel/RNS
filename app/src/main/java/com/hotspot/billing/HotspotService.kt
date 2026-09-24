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
import com.hotspot.billing.core.NetworkController
import com.hotspot.billing.core.UsageMonitor
import com.hotspot.billing.core.WatchdogManager
import com.hotspot.billing.db.HotspotSession
import com.hotspot.billing.net.ApHandle
import com.hotspot.billing.net.ApLauncher
import com.hotspot.billing.net.ApKind
import com.hotspot.billing.net.ApRadio
import com.hotspot.billing.net.IpPool
import com.hotspot.billing.net.LanPlan
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
 * Owns the billing gateway: root lifecycle, AP bring-up (WiFi Direct group
 * owner - the only method), NAT/DHCP/firewall, the captive portal, the
 * voucher expiry sweep, the watchdog and the debug log. Runs as a foreground
 * service so swiping the activity away does not kick paying users off the
 * network.
 *
 * **The service boots IDLE.** Creating the network, NAT and the portal only
 * happens when the user taps Start (ACTION_START). Stopping is ACTION_STOP.
 * A user stop is remembered (manually-stopped flag) so BootReceiver does not
 * bring the gateway back after a reboot against the user's last decision.
 *
 * Every step is written to [AppLog]: which interface appeared, which address
 * was adopted, which firewall rule was applied and what the watchdog found
 * afterwards. The debugger screen turns that into one copyable report.
 */
class HotspotService : android.app.Service() {

    enum class Phase { IDLE, STARTING, RUNNING, STOPPING, STOPPED, ERROR }

    /** Everything the admin UI displays; individual fields are volatile so the
     *  activity can poll them from the main thread safely. */
    class GatewayState {
        @Volatile var phase: Phase = Phase.IDLE
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
        /** 1..6 while a start is in flight - the badge's "STARTING… N/6". */
        @Volatile var startStep: Int = 0
        /** True when the last start failed ONLY because Location is not granted. */
        @Volatile var needsLocationPermission = false

        fun reset() {
            phase = Phase.IDLE
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
            startStep = 0
            needsLocationPermission = false
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
    private var portalSsid: String? = null

    // Core engine
    private lateinit var networkController: NetworkController
    private lateinit var watchdogManager: WatchdogManager
    private lateinit var deviceManager: DeviceManager
    private lateinit var billingManager: BillingManager
    private lateinit var usageMonitor: UsageMonitor
    private var currentApHandle: ApHandle? = null
    private var currentHotspotSessionId: Long? = null

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
        networkController = NetworkController(launcher)
        watchdogManager = WatchdogManager()
        deviceManager = DeviceManager(db)
        billingManager = BillingManager(db, voucherManager)
        usageMonitor = UsageMonitor(db)
        createChannel()
        registerStateReceivers()

        // The service boots IDLE: it deploys its scripts, checks root and shows
        // the status - it does NOT create the network. Start is the user's tap.
        state.phase = Phase.IDLE
        state.message = "Idle - press Start"
        startInForeground()
        log("service created (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT}, ${Build.MODEL})")
        log("service IDLE - the gateway only starts when the user taps Start (ACTION_START)")

        scope.launch {
            deployScripts()
            try {
                state.rootOk = RootShell.isRootAvailable()
            } catch (e: Throwable) {
                AppLog.e(AppLog.TAG_ROOT, "root check failed", e)
                state.rootOk = false
            }
            if (state.rootOk != true) {
                state.message = "Root not granted - allow this app in Magisk, then press Start"
            }
            log("service idle check: root=${state.rootOk ?: "unknown"}")
            updateNotification()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSequence()
            ACTION_STOP -> stopSequence()
            ACTION_DIAGNOSE -> scope.launch { runDiagnostics("requested from the notification") }
            else -> log("service started without an action - staying IDLE (Start is the user's tap)")
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
        // The user just asked for the gateway: a later reboot must start it.
        prefs.edit().putBoolean(KEY_MANUALLY_STOPPED, false).apply()
        log("user pressed Start (ACTION_START)")
        gatewayJob = scope.launch { runGateway() }
    }

    /** Tear everything down but keep the (silent) service alive. */
    fun stopSequence() {
        prefs.edit().putBoolean(KEY_MANUALLY_STOPPED, true).apply()
        if (state.phase != Phase.RUNNING && state.phase != Phase.STARTING &&
            state.phase != Phase.ERROR && state.phase != Phase.STOPPING
        ) {
            log("user pressed Stop (ACTION_STOP) - already idle, nothing to tear down")
            state.phase = Phase.STOPPED
            state.message = "Stopped"
            updateNotification()
            return
        }
        log("user pressed Stop (ACTION_STOP)")
        gatewayJob?.cancel()
        gatewayJob = scope.launch { teardown() }
    }

    /** Apply new settings: full stop, then start again. */
    fun restart() {
        prefs.edit().putBoolean(KEY_MANUALLY_STOPPED, false).apply()
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

    /**
     * Re-runs the start sequence on demand. Only when the user's last intent
     * was a start (a STARTING or a failed start) - a confirmed permission grant
     * must not start a gateway the user has stopped.
     */
    fun retryAp() {
        val phase = state.phase
        if (phase != Phase.STARTING && phase != Phase.ERROR) {
            log("AP retry requested but the gateway is $phase - not restarting")
            return
        }
        log("AP retry requested from the UI (permission grant confirmed)")
        prefs.edit().putBoolean(KEY_MANUALLY_STOPPED, false).apply()
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

        // Ensure default voucher plans exist (Phase 8)
        try {
            billingManager.ensureDefaultPlans()
        } catch (e: Throwable) {
            AppLog.w(AppLog.TAG_BILLING, "billing: ensureDefaultPlans failed ${e.message}")
        }

        val ssid = (prefs.getString(KEY_SSID, DEFAULT_SSID) ?: DEFAULT_SSID).ifBlank { DEFAULT_SSID }
        state.apMode = ApKind.WIFI_DIRECT.label

        // Gate AP creation on a CONFIRMED Location grant, not on a request that
        // was dispatched. createGroup (and its predecessors) throw or hide
        // themselves while the permission is missing.
        if (!ApRadio.hasLocationPermission(this)) {
            state.needsLocationPermission = true
            state.phase = Phase.ERROR
            state.message = "Location permission not confirmed - grant it (it happens automatically " +
                "when the dialog appears) and the gateway starts on its own."
            log("gateway NOT started: Location permission is not granted yet - the UI will ask, " +
                "and the start retries the moment the grant is confirmed")
            updateNotification()
            return
        }
        state.needsLocationPermission = false

        val startResult = withContext(Dispatchers.IO) {
            networkController.start(ssid, { log(it) }) { step ->
                state.startStep = step
            }
        }

        when (startResult) {
            is NetworkController.StartResult.Failed -> {
                state.phase = Phase.ERROR
                state.message = startResult.reason
                log("network: START failed at ${startResult.step}: ${startResult.reason}")
                updateNotification()
                return
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
                        mode = "wifi_direct",
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
                state.message = "Running on ${startResult.lanIf} via ${startResult.apHandle?.kind?.label ?: "WiFi Direct"} · " +
                    "http://${state.gatewayIp}/ · ${startResult.wanInfo.type} ${startResult.wanInfo.interfaceName}"
                log("gateway running: WAN=${startResult.wanInfo.interfaceName} LAN=${startResult.lanIf} " +
                    "gateway=${state.gatewayIp} - the portal should pop for each new client")
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

    /** One pass of the watchdog. Returns false when the gateway should stop. */
    private suspend fun monitorOnce(): Boolean {
        monitorTicks++
        val lan = SoftApController.apInterface(state.lanIf)

        // --- Use WatchdogManager for granular heal ---
        val watchdogResult = withContext(Dispatchers.IO) {
            watchdogManager.check(state.lanIf)
        }

        if (watchdogResult.issues.isNotEmpty()) {
            log("watchdog: check found issues: ${watchdogResult.issues.joinToString()}")
            val healed = withContext(Dispatchers.IO) {
                watchdogManager.heal(watchdogResult) { log(it) }
            }
            if (!healed && !watchdogResult.apAlive) {
                return rearmAfterApLoss()
            }
        }

        if (lan == null || !watchdogResult.apAlive) {
            log("watchdog: LAN interface ${state.lanIf ?: "?"} disappeared")
            return rearmAfterApLoss()
        }

        // Do NOT treat "address is not 10.66.0.1" as drift. Forcing that address
        // back every few seconds is what left clients looping on "Obtaining IP".
        val plan = RootShell.readLanPlan()
        val addrs = RootShell.lanAddresses(lan)
        val gateway = plan?.gateway
        val addrOk = gateway != null && addrs.any { it.substringBefore('/') == gateway }
        if (!addrOk) {
            log(
                "hotspot address is ${addrs.joinToString().ifBlank { "missing" }} " +
                    "(expected ${gateway ?: "unset"}) - adopting it"
            )
            if (!configureGateway(lan)) return false
            return true
        }

        val keep = RootShell.keepaliveNetwork()
        keep.out.forEach { if (it.isNotBlank()) log(it) }
        RootShell.readLanPlan()?.let { refreshed ->
            state.gatewayIp = refreshed.gateway
            state.dhcpOwner = refreshed.dhcpOwner
        }

        voucherManager.sweepExpired()
        refreshStats()

        // Usage monitoring every tick (Phase 11)
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

        // The deep health check is expensive (a dozen shell commands and an HTTP
        // probe), so it runs every DEEP_CHECK_EVERY_TICKS passes, not every one.
        if (monitorTicks % DEEP_CHECK_EVERY_TICKS == 0) {
            healthCheck(report = false)
        }
        updateNotification()
        return true
    }

    /**
     * The AP went away while we were running (WiFi toggled, radio reset, the
     * group evicted). Re-create it straight away - the gateway is supposed to
     * be up. If the re-creation fails the gateway reports ERROR and the user's
     * Start tap is the retry; there is no silent waiting loop.
     */
    private suspend fun rearmAfterApLoss(): Boolean {
        log("watchdog: re-creating the WiFi Direct group")
        state.phase = Phase.STARTING
        state.message = "Hotspot went off - re-creating the WiFi Direct group..."
        updateNotification()

        val ssid = (prefs.getString(KEY_SSID, DEFAULT_SSID) ?: DEFAULT_SSID).ifBlank { DEFAULT_SSID }
        val handle = withContext(Dispatchers.IO) { launcher.launch(ssid) { log(it) } }
        val iface = handle?.interfaceName
        if (iface == null) {
            state.phase = Phase.ERROR
            state.message = "Could not re-create the WiFi Direct group after it was lost " +
                "(see the debugger). Tap Start to retry."
            log("watchdog: re-arm FAILED - no group could be created")
            updateNotification()
            return false
        }
        currentApHandle = handle
        applyHandle(handle)
        state.lanIf = iface
        withContext(Dispatchers.IO) { launcher.waitForAddress(iface, ADDRESS_WAIT_MS) { log(it) } }
        if (!configureGateway(iface)) return false
        return true
    }

    /**
     * Collects the watchdog's view of the gateway and turns it into findings.
     * New findings are logged once (with their H-code); recovered ones are
     * logged once as well, so the log reads as a story instead of a broken
     * record.
     */
    private suspend fun healthCheck(report: Boolean): List<Finding> = withContext(Dispatchers.IO) {
        val lan = state.lanIf
        val interfaces = try {
            RootShell.interfaces()
        } catch (e: Throwable) {
            emptyList()
        }
        val lanExists = lan != null && interfaces.any { it.first == lan }
        val addresses = if (lan != null && lanExists) {
            try { RootShell.lanAddresses(lan) } catch (e: Throwable) { emptyList() }
        } else {
            emptyList()
        }
        val plan = RootShell.readLanPlan()
        val probe = if (report || state.phase == Phase.RUNNING) {
            val gw = plan?.gateway ?: state.gatewayIp
            if (gw != null && state.portalRunning) {
                HttpProbe.get("http://$gw/generate_204", 3_000).status
            } else {
                null
            }
        } else {
            null
        }
        val authorized = try {
            RootShell.run("cat /data/local/tmp/authorized_macs.txt 2>/dev/null", quiet = true)
                .out.count { it.isNotBlank() }
        } catch (e: Throwable) {
            0
        }

        val input = HealthInput(
            lanIf = lan,
            lanInterfaceExists = lanExists,
            lanAddresses = addresses,
            expectedGateway = plan?.gateway,
            ipForwardEnabled = try { RootShell.ipForwardEnabled() } catch (e: Throwable) { null },
            ourDnsmasqRunning = try { RootShell.isDnsmasqRunning() } catch (e: Throwable) { false },
            foreignDnsmasqRunning = try { RootShell.isForeignDnsmasqRunning() } catch (e: Throwable) { false },
            dhcpOwner = plan?.dhcpOwner ?: state.dhcpOwner,
            portalAlive = portal?.isAlive == true,
            portalProbeStatus = probe,
            natJumpFirst = try {
                RootShell.jumpIsFirst("nat", "PREROUTING", "HS_NAT")
            } catch (e: Throwable) { null },
            forwardJumpFirst = try {
                RootShell.jumpIsFirst("filter", "FORWARD", "HS_FWD")
            } catch (e: Throwable) { null },
            wanIf = state.wanIf,
            defaultRouteIf = try { RootShell.defaultRouteInterface() } catch (e: Throwable) { null },
            policyRoutingOk = if (lan != null && lanExists) {
                try { RootShell.policyRoutingOk(lan) } catch (e: Throwable) { null }
            } else {
                null
            },
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
                    "(dnsmasq ours=${input.ourDnsmasqRunning} android=${input.foreignDnsmasqRunning}, " +
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
        val ssid = state.apSsid
        val pass = state.apPassword
        if (portal != null && (portalGateway != gateway || portalSsid != ssid)) {
            try { portal?.stop() } catch (e: Exception) { /* rebound onto the new gateway */ }
            portal = null
        }
        if (portal == null) {
            portal = CaptivePortalServer(voucherManager, gateway, ssid, pass) { AppLog.i(AppLog.TAG_PORTAL, it) }
            try {
                portal?.start()
                log("captive portal listening on 0.0.0.0:${CaptivePortalServer.PORT} " +
                    "(gateway $gateway, join card: ${ssid ?: "no SSID yet"} / ${pass ?: "?"})")
            } catch (e: Exception) {
                log("captive portal failed to start: ${e.message}")
                AppLog.e(AppLog.TAG_PORTAL, "portal start failed", e)
            }
            portalGateway = gateway
            portalSsid = ssid
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
        log("stopping gateway")
        // The button shows "Stopped" during this whole window and is disabled,
        // so a second tap cannot race the teardown.
        state.phase = Phase.STOPPING
        state.message = "Stopping..."
        updateNotification()
        try { portal?.stop() } catch (e: Exception) { /* already stopped */ }
        portal = null
        portalGateway = null
        portalSsid = null
        state.portalRunning = false

        // Use NetworkController STOP for clean shutdown
        try {
            networkController.stop(currentApHandle) { log(it) }
        } catch (e: Throwable) {
            AppLog.w(AppLog.TAG_SERVICE, "networkController stop failed ${e.message}")
            // Fallback to old method
            try { RootShell.stopBandwidth() } catch (e2: Exception) { /* nothing to stop */ }
            try { RootShell.stopNetwork() } catch (e2: Exception) { /* nothing to stop */ }
        }

        currentApHandle?.close { log(it) }
        currentApHandle = null

        // Close hotspot session
        currentHotspotSessionId?.let { id ->
            try {
                db.hotspotSessionDao().close(id, System.currentTimeMillis(), "STOPPED", 0)
                log("hotspot session $id closed")
            } catch (e: Throwable) {
                AppLog.w(AppLog.TAG_SERVICE, "session close failed ${e.message}")
            }
            currentHotspotSessionId = null
        }

        launcher.release { log(it) }
        state.reset()
        state.phase = Phase.STOPPED
        state.message = "Stopped"
        reportedFindings.clear()
        log("gateway stopped - teardown confirmed complete (the Start button is back)")
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
        val leases = RootShell.connectedClients(state.lanIf)
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

    // ---------------------------------------------------------------- system state receivers

    /**
     * Logs what the system does to WiFi/the AP while we run, and re-arms the
     * gateway when the internet side moves. Everything here is diagnostic
     * first: a state change that we only learn about from logcat is a state
     * change we cannot react to.
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
                    if (info?.isConnected == false && state.phase == Phase.RUNNING) {
                        log("system broadcast: our P2P group lost its connection - the watchdog re-arms it")
                    }
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
            .setContentTitle("RNS Hotspot - ${state.phase.name.lowercase()}")
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
        const val KEY_LOGCAT_MODE = "logcat_mode"
        /**
         * The user's last Start/Stop decision, so BootReceiver does not bring
         * the gateway back after a reboot when it was the one who stopped it.
         */
        const val KEY_MANUALLY_STOPPED = "manually_stopped"
        const val DEFAULT_SSID = "RNS-Hotspot"

        /** Not in the SDK: the string itself has been stable since Android 2. */
        private const val AP_STATE_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val EXTRA_AP_STATE = "wifi_state"

        private const val SCRIPT_DIR = "/data/local/tmp"
        private val SCRIPTS = listOf("setup_network.sh", "bandwidth_control.sh", "netshare_ap.sh")
        private const val MONITOR_INTERVAL_MS = 8_000L
        private const val ADDRESS_WAIT_MS = 12_000L
        private const val DEEP_CHECK_EVERY_TICKS = 8
        private const val MAX_LOG_LINES = 400
        private const val DEVICE_SEEN_UPDATE_MS = 5 * 60_000L
        private const val CHANNEL_ID = "hotspot_status"
        private const val NOTIF_ID = 42
    }
}
