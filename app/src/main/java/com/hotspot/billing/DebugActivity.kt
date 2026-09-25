package com.hotspot.billing

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.debug.CrashGuard
import com.hotspot.billing.debug.DebugExport
import com.hotspot.billing.debug.Finding
import com.hotspot.billing.debug.LogFormat
import com.hotspot.billing.debug.LogLevel
import com.hotspot.billing.debug.LogcatWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The debugger screen: a live tail of everything the app recorded, plus one
 * button that produces the full diagnostic report.
 *
 * Designed for one job - getting the text out of the phone so somebody can read
 * it. Long-press selection works, but **Copy all**, **Share** and **Save .txt**
 * exist so that nobody has to select 4 000 lines by hand.
 */
class DebugActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CrashGuard.handler())
    private val handler = Handler(Looper.getMainLooper())

    private var svc: HotspotService? = null
    private var bound = false

    private lateinit var statusView: TextView
    private lateinit var hintView: TextView
    private lateinit var textView: TextView
    private lateinit var footerView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var progress: ProgressBar
    private lateinit var tailButton: Button

    /** What is on screen right now: the live tail, or a full report. */
    private var reportText: String? = null
    private var uiMinLevel = LogLevel.INFO
    private var tail = true
    private var lastSavedFile: File? = null

    private val poller = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            renderStatus()
            if (tail && reportText == null) renderLogTail()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            svc = (service as? HotspotService.LocalBinder)?.service()
            renderStatus()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            svc = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_debug)
        AppLog.init(this)

        statusView = findViewById(R.id.debug_status)
        hintView = findViewById(R.id.debug_hint)
        textView = findViewById(R.id.debug_text)
        footerView = findViewById(R.id.debug_footer)
        scrollView = findViewById(R.id.debug_scroll)
        progress = findViewById(R.id.debug_progress)
        tailButton = findViewById(R.id.btn_tail)

        wireButtons()
        // Attach without BIND_AUTO_CREATE: opening the debugger must never start
        // (or restart) the gateway.
        bound = bindService(Intent(this, HotspotService::class.java), connection, 0)
        if (!bound) {
            statusView.text = "the gateway service is not running - showing the log recorded so far"
        }
        renderLogTail()
        renderStatus()
        handler.post(poller)

        AppLog.i(AppLog.TAG_UI, "debugger opened")
    }

    override fun onDestroy() {
        handler.removeCallbacks(poller)
        if (bound) {
            try {
                unbindService(connection)
            } catch (e: Exception) {
                // already gone
            }
            bound = false
        }
        scope.cancel()
        super.onDestroy()
    }

    // ------------------------------------------------------------------ buttons

    private fun wireButtons() {
        findViewById<Button>(R.id.btn_copy_all).setOnClickListener { export { text ->
            val chars = DebugExport.copyToClipboard(this, text)
            toast("Copied ${chars} characters - paste it anywhere")
        } }

        findViewById<Button>(R.id.btn_share).setOnClickListener { export { text ->
            DebugExport.share(this, text)
        } }

        findViewById<Button>(R.id.btn_save_file).setOnClickListener { export { text ->
            val file = DebugExport.saveToFile(this, text, "RNS-debug")
            lastSavedFile = file
            renderFooter()
            if (file != null) {
                toast("Saved ${file.name}\n${file.absolutePath}")
                DebugExport.shareFile(this, file)
            } else {
                toast("Could not write the file - use Copy all instead")
            }
        } }

        findViewById<Button>(R.id.btn_full_report).setOnClickListener { buildFullReport() }

        findViewById<Button>(R.id.btn_back_to_log).setOnClickListener {
            reportText = null
            textView.text = ""
            renderLogTail()
            renderFooter()
            toast("Back to the live log")
        }

        findViewById<Button>(R.id.btn_check_now).setOnClickListener { runHealthCheck() }

        findViewById<Button>(R.id.btn_clear_log).setOnClickListener {
            AppLog.clear()
            reportText = null
            renderLogTail()
            toast("Log cleared")
        }

        findViewById<Button>(R.id.btn_level_all).setOnClickListener { setLevel(LogLevel.VERBOSE) }
        findViewById<Button>(R.id.btn_level_info).setOnClickListener { setLevel(LogLevel.INFO) }
        findViewById<Button>(R.id.btn_level_warn).setOnClickListener { setLevel(LogLevel.WARN) }

        findViewById<Button>(R.id.btn_logcat_off).setOnClickListener {
            setLogcatMode(LogcatWatcher.Mode.OFF)
        }
        findViewById<Button>(R.id.btn_logcat_net).setOnClickListener {
            setLogcatMode(LogcatWatcher.Mode.FILTERED)
        }
        findViewById<Button>(R.id.btn_logcat_all).setOnClickListener {
            setLogcatMode(LogcatWatcher.Mode.EVERYTHING)
        }

        tailButton = findViewById(R.id.btn_tail)
        tailButton.setOnClickListener {
            tail = !tail
            tailButton.text = if (tail) "Pause" else "Resume"
            toast(if (tail) "Live tail on" else "Live tail paused (the log keeps recording)")
        }
    }

    private fun setLevel(level: LogLevel) {
        uiMinLevel = level
        if (reportText == null) renderLogTail()
        renderFooter()
    }

    private fun setLogcatMode(mode: LogcatWatcher.Mode) {
        val service = svc
        if (service != null) {
            service.applyLogcatMode(mode)
        } else {
            LogcatWatcher.start(mode, root = false)
        }
        renderStatus()
        toast("System log capture: ${mode.label}")
    }

    // ------------------------------------------------------------------ rendering

    private fun renderStatus() {
        val state = svc?.state
        val counts = AppLog.counts()
        val summary = counts.entries.joinToString(" ") { "${it.key.letter}=${it.value}" }
        statusView.text = buildString {
            append("gateway: ").append(state?.phase ?: "service not bound")
            append("  ·  lan=").append(state?.lanIf ?: "-")
            append("  ·  wan=").append(state?.wanIf ?: "-")
            append("  ·  gw=").append(state?.gatewayIp ?: "-")
            append('\n')
            append("ap: ").append(state?.apKind ?: "-")
            append("  ·  ssid=").append(state?.apSsid ?: "-")
            append("  ·  mode=").append(state?.apMode ?: "-")
            append('\n')
            append("log: ").append(AppLog.size()).append(" lines in memory (")
            append(summary).append(")")
            append("  ·  ").append(LogcatWatcher.status())
            append('\n')
            append("last health check: ").append(state?.lastHealthCheck ?: "not yet")
            val findings = state?.findings ?: emptyList()
            if (findings.isNotEmpty()) {
                append('\n').append("findings: ").append(findings.joinToString { it.code })
            }
        }

        val headline = state?.findings?.firstOrNull { it.level.priority >= LogLevel.WARN.priority }
        if (headline != null) {
            hintView.visibility = View.VISIBLE
            hintView.text = "${headline.code} ${headline.problem}\nfix: ${headline.hint}"
        } else if (reportText != null) {
            hintView.visibility = View.VISIBLE
            hintView.text = "Showing the full diagnostic report. Use Copy all / Share to send it. " +
                "\"Live log\" goes back to the tail."
        } else {
            hintView.visibility = View.GONE
        }
        renderFooter()
    }

    private fun renderFooter() {
        val file = AppLog.logFile()
        footerView.text = buildString {
            append("showing ")
            append(if (reportText != null) "the full report" else "level ${uiMinLevel.letter}+")
            append("  ·  log file: ").append(file?.absolutePath ?: "(none)")
            lastSavedFile?.let { append("\nsaved: ").append(it.absolutePath) }
            append("\nCopy all / Share always export the whole thing, not just what is on screen.")
        }
    }

    /** On-screen tail: the newest [SCREEN_LINES] records, compact format. */
    private fun renderLogTail() {
        val lines = AppLog.snapshot(limit = SCREEN_LINES, min = uiMinLevel)
        val atBottom = isScrolledToBottom()
        textView.text = if (lines.isEmpty()) {
            "(nothing recorded at level ${uiMinLevel.letter}+ yet - press All to see every record)"
        } else {
            LogFormat.lines(lines, compact = true)
        }
        if (atBottom || tail) scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun isScrolledToBottom(): Boolean {
        val child = scrollView.getChildAt(0) ?: return true
        return scrollView.scrollY + scrollView.height >= child.height - 40
    }

    // ------------------------------------------------------------------ actions

    /** Produces the text to export (off the main thread) and hands it to [block]. */
    private fun export(block: (String) -> Unit) {
        progress.visibility = View.VISIBLE
        scope.launch {
            val text = withContext(Dispatchers.IO) { exportText() }
            progress.visibility = View.GONE
            block(text)
        }
    }

    private fun exportText(): String {
        reportText?.let { return it }
        return buildString {
            append("=== RNS hotspot gateway - event log ===\n")
            append("exported  : ").append(LogFormat.timestamp(System.currentTimeMillis())).append('\n')
            append("device    : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            val service = svc
            if (service != null) {
                val s = service.snapshot()
                append("phase     : ").append(s.phase).append('\n')
                append("ap        : ").append(s.apKind ?: "-").append(" mode=").append(s.apMode ?: "-")
                    .append(" ssid=").append(s.apSsid ?: "-").append('\n')
                append("lan/wan   : ").append(s.lanIf ?: "-").append(" / ").append(s.wanIf ?: "-")
                    .append(" gateway=").append(s.gatewayIp ?: "-")
                    .append(" dhcp=").append(s.dhcpOwner ?: "-").append('\n')
                val findings = service.findings()
                append("findings  : ")
                    .append(if (findings.isEmpty()) "none" else findings.joinToString { it.code })
                    .append('\n')
            } else {
                append("gateway   : service not running\n")
            }
            append("records   : ").append(AppLog.size()).append(" in memory, level ")
                .append(uiMinLevel.letter).append("+\n")
            append("NOTE: press \"Full report\" for the device/firewall/DHCP dump too.\n")
            append("----------------------------------------\n")
            append(AppLog.text(limit = EXPORT_LINES, min = uiMinLevel))
        }
    }

    private fun buildFullReport() {
        val service = svc
        if (service == null) {
            toast("The gateway service is not running - open the app first, then try again")
            return
        }
        progress.visibility = View.VISIBLE
        tail = false
        tailButton.text = "Resume"
        statusView.text = "collecting the full report (interfaces, firewall, DHCP, portal, logcat)..."
        scope.launch {
            val report = try {
                withContext(Dispatchers.IO) { service.buildReport() }
            } catch (e: Throwable) {
                AppLog.e(AppLog.TAG_SERVICE, "building the report failed", e)
                "Building the report failed: ${e.javaClass.simpleName}: ${e.message}\n\n" +
                    AppLog.text(limit = 400)
            }
            progress.visibility = View.GONE
            reportText = report
            textView.text = if (report.length > SCREEN_REPORT_CHARS) {
                report.take(SCREEN_REPORT_CHARS) +
                    "\n\n... (${report.length - SCREEN_REPORT_CHARS} more characters - " +
                    "Copy all / Share has the complete report) ..."
            } else {
                report
            }
            scrollView.post { scrollView.scrollTo(0, 0) }
            renderStatus()
            toast("Report ready (${report.length} chars) - press Copy all or Share")
        }
    }

    private fun runHealthCheck() {
        val service = svc
        if (service == null) {
            toast("The gateway service is not running")
            return
        }
        progress.visibility = View.VISIBLE
        scope.launch {
            val findings: List<Finding> = try {
                service.checkNow()
            } catch (e: Throwable) {
                AppLog.e(AppLog.TAG_WATCHDOG, "manual health check failed", e)
                emptyList()
            }
            progress.visibility = View.GONE
            renderStatus()
            renderLogTail()
            toast(
                if (findings.isEmpty()) "Health check: no problems found"
                else "Health check: " + findings.joinToString { "${it.code} ${it.problem}" }
            )
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val REFRESH_MS = 2_000L
        private const val SCREEN_LINES = 400
        private const val EXPORT_LINES = 5_000
        private const val SCREEN_REPORT_CHARS = 150_000

        fun intent(context: Context): Intent = Intent(context, DebugActivity::class.java)
    }
}
