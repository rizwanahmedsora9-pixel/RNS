package com.hotspot.billing.debug

import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Streams the device's logcat into [AppLog] so the debugger also sees what the
 * *system* did - `wpa_supplicant`, `hostapd`, `Tethering`, `IpServer`,
 * `WifiP2pService`, `netd`, `dnsmasq`. Those are the components that decide
 * whether an AP comes up and whether DHCP works, and none of them log to us.
 *
 * Runs through a plain [Runtime.exec] rather than the libsu shell on purpose:
 * a streaming command submitted to the shared root shell would queue every
 * other root command behind it and the gateway would appear to hang.
 *
 * With root this is the whole log (filtered to networking tags by default).
 * Without root Android only hands back our own app's lines - still useful, and
 * it degrades instead of failing.
 */
object LogcatWatcher {

    enum class Mode(val key: String, val label: String) {
        OFF("off", "Off"),
        FILTERED("filtered", "WiFi / network tags"),
        EVERYTHING("all", "Everything (noisy)");

        companion object {
            fun from(key: String?): Mode = values().firstOrNull { it.key == key } ?: FILTERED
        }
    }

    /** Tags worth watching, in `logcat tag:priority` form. */
    private val NET_TAGS = listOf(
        "wifi", "WifiService", "WifiNative", "WifiVendorHal", "SupplicantStaIfaceHal",
        "wpa_supplicant", "hostapd", "SoftApManager", "WifiApConfigStore", "Tethering",
        "WifiTethering", "IpServer", "IpClient", "DhcpServer", "dhcpcd", "dnsmasq",
        "WifiP2pService", "WifiP2pManager", "p2p_supplicant", "NetworkManagementService",
        "ConnectivityService", "NetworkAssistant", "netd", "NetworkStats", "EthernetTracker",
        "hotspot.billing", "AndroidRuntime", "System.err"
    )

    private val running = AtomicBoolean(false)
    @Volatile private var process: Process? = null
    @Volatile private var thread: Thread? = null
    @Volatile private var mode: Mode = Mode.OFF
    @Volatile private var restarts = 0
    @Volatile private var lastError: String? = null
    @Volatile var lineCount: Long = 0
        private set

    fun isRunning(): Boolean = running.get()

    fun currentMode(): Mode = mode

    fun status(): String = when {
        !running.get() -> "stopped (mode ${mode.label})"
        else -> "running, mode=${mode.label}, ${lineCount} line(s), restarts=$restarts" +
            (lastError?.let { ", last error: $it" } ?: "")
    }

    /**
     * Starts (or restarts in a new mode) the stream. Blocking-safe: the process
     * is spawned and a daemon thread reads it.
     */
    @Synchronized
    fun start(mode: Mode, root: Boolean) {
        this.mode = mode
        if (mode == Mode.OFF) {
            stopInternal("mode set to Off")
            return
        }
        if (running.get()) {
            // Already streaming: only a mode change needs a restart.
            stopInternal("mode changed")
        }
        running.set(true)
        restarts = 0
        spawn(mode, root)
    }

    @Synchronized
    fun stop() {
        mode = Mode.OFF
        stopInternal("stopped from the UI")
    }

    private fun stopInternal(reason: String) {
        running.set(false)
        val proc = process
        process = null
        if (proc != null) {
            try {
                proc.destroy()
            } catch (ignored: Throwable) {
            }
        }
        val t = thread
        thread = null
        t?.interrupt()
        if (reason.isNotBlank()) AppLog.d(AppLog.TAG_LOGCAT, "logcat watcher $reason")
    }

    private fun spawn(mode: Mode, root: Boolean) {
        val command = buildCommand(mode, root)
        AppLog.i(AppLog.TAG_LOGCAT, "starting: $command")
        val proc = try {
            Runtime.getRuntime().exec(splitCommand(command))
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            AppLog.e(AppLog.TAG_LOGCAT, "could not start logcat ($lastError)", e)
            running.set(false)
            return
        }
        process = proc

        val reader = Thread({
            try {
                BufferedReader(InputStreamReader(proc.inputStream)).use { input ->
                    while (true) {
                        val line = input.readLine() ?: break
                        ingest(line)
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    lastError = e.message ?: e.javaClass.simpleName
                    AppLog.w(AppLog.TAG_LOGCAT, "logcat stream ended: $lastError")
                }
            } finally {
                drainAndRestart(proc, root)
            }
        }, "logcat-reader").apply { isDaemon = true }
        thread = reader
        reader.start()

        // stderr must be drained or the child blocks once the pipe fills.
        Thread({
            try {
                BufferedReader(InputStreamReader(proc.errorStream)).use { err ->
                    while (true) {
                        val line = err.readLine() ?: break
                        if (line.isNotBlank()) {
                            lastError = line
                            AppLog.w(AppLog.TAG_LOGCAT, "logcat stderr: $line")
                        }
                    }
                }
            } catch (ignored: Throwable) {
            }
        }, "logcat-stderr").apply { isDaemon = true }.start()
    }

    private fun drainAndRestart(proc: Process, root: Boolean) {
        if (!running.get() || process !== proc) return
        if (restarts >= MAX_RESTARTS) {
            AppLog.w(AppLog.TAG_LOGCAT, "logcat watcher gave up after $restarts restarts")
            running.set(false)
            return
        }
        restarts++
        AppLog.w(AppLog.TAG_LOGCAT, "logcat stream died - restarting (attempt $restarts)")
        try {
            Thread.sleep(2_000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return
        }
        if (running.get()) spawn(mode, root)
    }

    private fun ingest(raw: String) {
        if (raw.isBlank()) return
        lineCount++
        val parsed = parseThreadtime(raw)
        val level = parsed?.first ?: LogLevel.INFO
        val tag = parsed?.second
        AppLog.log(
            level,
            if (tag == null) AppLog.TAG_LOGCAT else "${AppLog.TAG_LOGCAT}:${tag.take(24)}",
            raw
        )
    }

    /** `09-23 21:04:11.234  1234  1234 D wifi    : message` -> (DEBUG, "wifi"). */
    fun parseThreadtime(line: String): Pair<LogLevel, String>? {
        // date time pid tid LEVEL tag: message
        val parts = line.split(Regex("\\s+"), limit = 7)
        if (parts.size < 7) return null
        val levelChar = parts[4]
        if (levelChar.length != 1) return null
        val level = LogLevel.fromLetter(levelChar[0]) ?: return null
        var tag = parts[5]
        if (tag.endsWith(":")) tag = tag.dropLast(1)
        if (tag.isEmpty()) return null
        return level to tag
    }

    /** The full shell command, kept in one place so the debugger can show it. */
    fun buildCommand(mode: Mode, root: Boolean): String {
        val buffers = "-b main -b system -b crash"
        return if (root) {
            val spec = if (mode == Mode.EVERYTHING) "-v threadtime $buffers" else {
                val tags = NET_TAGS.joinToString(" ") { "$it:V" }
                "-v threadtime $buffers $tags *:S"
            }
            "su -c \"logcat $spec\""
        } else {
            // No root: Android only returns this app's own lines.
            "logcat -v threadtime --pid=${android.os.Process.myPid()}"
        }
    }

    /** Shell words, honouring the double quotes used for `su -c "..."`. */
    fun splitCommand(command: String): Array<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (c in command) {
            when {
                c == '"' -> inQuotes = !inQuotes
                c.isWhitespace() && !inQuotes -> {
                    if (current.isNotEmpty()) {
                        out.add(current.toString())
                        current.setLength(0)
                    }
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) out.add(current.toString())
        return out.toTypedArray()
    }

    /**
     * One-shot capture (`logcat -d`) used by the diagnostic report. Returns the
     * text, or a one-line explanation of why there is none.
     */
    fun dump(maxChars: Int = 60_000, root: Boolean = false, filtered: Boolean = true): String {
        val spec = if (root && filtered) {
            NET_TAGS.joinToString(" ") { "$it:V" } + " *:S"
        } else {
            ""
        }
        val cmd = if (root) "su -c \"logcat -d -v threadtime -b main -b system -b crash -t 800 $spec\""
        else "logcat -d -v threadtime -t 800"
        return try {
            val proc = Runtime.getRuntime().exec(splitCommand(cmd))
            val text = proc.inputStream.bufferedReader().use { it.readText() }
            proc.waitFor()
            val trimmed = text.trim()
            if (trimmed.isEmpty()) "(logcat returned nothing for: $cmd)"
            else if (trimmed.length > maxChars) "...(trimmed)...\n" + trimmed.takeLast(maxChars)
            else trimmed
        } catch (e: Exception) {
            "(logcat capture failed: ${e.message} - cmd: $cmd)"
        }
    }

    private const val MAX_RESTARTS = 5
}
