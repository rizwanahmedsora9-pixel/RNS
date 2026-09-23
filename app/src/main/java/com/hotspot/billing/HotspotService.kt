package com.hotspot.billing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.hotspot.billing.db.AppDatabase
import com.hotspot.billing.db.DeviceProfile
import com.hotspot.billing.net.LanPlan
import com.hotspot.billing.net.IpPool
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns the entire billing gateway: root lifecycle, AP bring-up, NAT/DHCP/firewall,
 * the captive portal, the voucher expiry sweep and re-apply when the hotspot
 * interface bounces. Runs as a foreground service so swiping the activity away
 * does not kick paying users off the network.
 */
class HotspotService : Service() {

    enum class Phase { STARTING, WAITING_AP, RUNNING, STOPPED, ERROR }

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
        }
    }

    inner class LocalBinder : android.os.Binder() {
        fun service(): HotspotService = this@HotspotService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var gatewayJob: Job? = null

    val state = GatewayState()

    private lateinit var db: AppDatabase
    private lateinit var voucherManager: VoucherManager
    private lateinit var prefs: SharedPreferences
    private var portal: CaptivePortalServer? = null
    private var portalGateway: String? = null

    private val logBuffer = ArrayDeque<String>()
    private val logLock = Any()

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.get(this)
        voucherManager = VoucherManager(db)
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        createChannel()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSequence()
            else -> startSequence()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        gatewayJob?.cancel()
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
        gatewayJob?.cancel()
        gatewayJob = scope.launch { teardown() }
    }

    /** Apply new settings: full stop, then start again. */
    fun restart() {
        gatewayJob?.cancel()
        gatewayJob = scope.launch {
            teardown()
            runGateway()
        }
    }

    fun dumpLog(): List<String> = synchronized(logLock) { logBuffer.toList() }

    // ---------------------------------------------------------------- gateway logic

    private suspend fun runGateway() {
        state.reset()
        state.phase = Phase.STARTING
        state.message = "Starting..."
        updateNotification()

        if (!ensureRoot()) return

        log("deploying network scripts")
        deployScripts()

        val ssid = prefs.getString(KEY_SSID, DEFAULT_SSID) ?: DEFAULT_SSID
        val pass = prefs.getString(KEY_PASS, DEFAULT_PASS) ?: DEFAULT_PASS
        SoftApController.startAp(ssid, pass) { log(it) }

        // Give the AP a moment to appear; on Android 9/10 nothing above can start
        // it programmatically, so we fall through to waiting for a manual toggle.
        var lan = waitForLanInterface(15_000)
        if (lan == null) {
            state.phase = Phase.WAITING_AP
            state.message = "Hotspot is off. Switch it on from quick settings or press " +
                "\u201cOpen Android hotspot settings\u201d - the gateway takes over automatically."
            log("waiting for the hotspot to be switched on")
            updateNotification()
            lan = waitForLanInterface(Long.MAX_VALUE)
            if (lan == null) return // cancelled
        }

        if (!configureGateway(lan)) return

        // Cancellation (Stop / restart) exits through delay() throwing.
        while (true) {
            delay(MONITOR_INTERVAL_MS)
            if (!monitorOnce()) return
        }
    }

    /** One pass of the watchdog. Returns false when the gateway should stop. */
    private suspend fun monitorOnce(): Boolean {
        val lan = SoftApController.apInterface(state.lanIf)
        if (lan == null) {
            state.phase = Phase.WAITING_AP
            state.message = "Hotspot went off - switch it back on, the gateway re-arms itself."
            log("LAN interface disappeared - waiting for the hotspot again")
            updateNotification()
            val back = waitForLanInterface(Long.MAX_VALUE) ?: return false
            if (!configureGateway(back)) return false
            return true
        }

        // Do NOT treat "address is not 10.66.0.1" as drift. Forcing that address
        // back every few seconds is what left clients looping on "Obtaining IP".
        // Adopt whatever is on the interface, and let keepalive heal DHCP
        // without flushing it.
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
        updateNotification()
        return true
    }

    private suspend fun ensureRoot(): Boolean = withContext(Dispatchers.IO) {
        val ok = try {
            RootShell.isRootAvailable()
        } catch (e: Exception) {
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

        val res = RootShell.startNetwork()
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
            portal = CaptivePortalServer(voucherManager, gateway) { log(it) }
            try {
                portal?.start()
            } catch (e: Exception) {
                log("captive portal failed to start: ${e.message}")
            }
            portalGateway = gateway
        }
        state.portalRunning = portal?.isAlive ?: false
    }

    private fun runningMessage(lanIf: String, plan: LanPlan): String {
        val dhcp = when (plan.dhcpOwner) {
            "android" -> "phone DHCP"
            "failed" -> "DHCP DOWN"
            else -> "app DHCP"
        }
        return "Running on $lanIf · http://${plan.gateway}/ · $dhcp. " +
            "Stuck on Obtaining IP? Forget the Wi-Fi and rejoin."
    }

    private suspend fun teardown() = withContext(Dispatchers.IO) {
        log("stopping gateway")
        try { portal?.stop() } catch (e: Exception) { /* already stopped */ }
        portal = null
        portalGateway = null
        state.portalRunning = false
        try { RootShell.stopBandwidth() } catch (e: Exception) { /* nothing to stop */ }
        try { RootShell.stopNetwork() } catch (e: Exception) { /* nothing to stop */ }
        SoftApController.stopAp { log(it) }
        state.reset()
        state.phase = Phase.STOPPED
        state.message = "Stopped"
        log("gateway stopped")
        updateNotification()
    }

    /** Polls for the hotspot interface; returns it, or null on timeout/cancel. */
    private suspend fun waitForLanInterface(timeoutMs: Long): String? {
        val started = SystemClock.elapsedRealtime()
        while (true) {
            val pinned = prefs.getString(KEY_LAN_IF, null)?.trim()?.takeIf { it.isNotEmpty() }
            SoftApController.apInterface(pinned)?.let { return it }
            if (timeoutMs != Long.MAX_VALUE &&
                SystemClock.elapsedRealtime() - started > timeoutMs
            ) return null
            delay(POLL_INTERVAL_MS)
        }
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
        for (name in listOf("setup_network.sh", "bandwidth_control.sh")) {
            val tmpLocal = java.io.File(cacheDir, name)
            assets.open(name).use { input ->
                tmpLocal.outputStream().use { output -> input.copyTo(output) }
            }
            RootShell.run("cp ${tmpLocal.absolutePath} $SCRIPT_DIR/$name && chmod 755 $SCRIPT_DIR/$name")
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
            } else if (existing.hostname != lease.hostname || now - existing.lastSeen > DEVICE_SEEN_UPDATE_MS) {
                val hostname = lease.hostname.ifBlank { existing.hostname }
                dao.upsert(existing.copy(hostname = hostname, lastSeen = now))
            }
        }
    }

    // ---------------------------------------------------------------- logging + notification

    private fun log(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(logLock) {
            logBuffer.addLast("[$stamp] $line")
            while (logBuffer.size > MAX_LOG_LINES) logBuffer.removeFirst()
        }
        android.util.Log.d(TAG, line)
        updateNotification()
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
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID, buildNotification())
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, HotspotService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("RNS Hotspot")
            .setContentText(state.message.ifBlank { state.phase.name.lowercase() })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    companion object {
        private const val TAG = "HotspotService"
        const val ACTION_START = "com.hotspot.billing.START"
        const val ACTION_STOP = "com.hotspot.billing.STOP"

        const val PREFS = "hotspot_prefs"
        const val KEY_SSID = "ssid"
        const val KEY_PASS = "pass"
        const val KEY_WAN_IF = "wan_if"
        const val KEY_LAN_IF = "lan_if"
        const val DEFAULT_SSID = "RNS-Hotspot"
        const val DEFAULT_PASS = "hotspot123"

        private const val SCRIPT_DIR = "/data/local/tmp"
        private const val MONITOR_INTERVAL_MS = 8_000L
        private const val POLL_INTERVAL_MS = 2_000L
        private const val MAX_LOG_LINES = 400
        private const val DEVICE_SEEN_UPDATE_MS = 5 * 60_000L
        private const val CHANNEL_ID = "hotspot_status"
        private const val NOTIF_ID = 42
    }
}
