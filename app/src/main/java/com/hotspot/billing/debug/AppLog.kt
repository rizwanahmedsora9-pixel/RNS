package com.hotspot.billing.debug

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The app-wide debug recorder: one ring buffer + one on-disk file that every
 * layer writes to, so a single copy/paste shows what the gateway actually did.
 *
 * What ends up here:
 *  - every root shell command with its exit code, stdout, stderr and duration ([com.hotspot.billing.util.RootShell])
 *  - every gateway phase change, AP start attempt and its failure reason ([com.hotspot.billing.HotspotService])
 *  - watchdog findings, each with a stable code like `W3 dnsmasq not running`
 *  - streamed logcat lines (tagged `logcat`), including the system's WiFi/tethering chatter
 *  - uncaught exceptions and coroutine failures ([CrashGuard])
 *
 * The buffer is capped and rate-limited: a logcat flood must not push out the
 * gateway's own events, and logging must never block the network thread.
 */
object AppLog {

    /** Tags used across the app, so the UI filter and reports stay consistent. */
    const val TAG_SERVICE = "gateway"
    const val TAG_ROOT = "root"
    const val TAG_AP = "ap"
    const val TAG_WATCHDOG = "watchdog"
    const val TAG_PORTAL = "portal"
    const val TAG_VOUCHER = "voucher"
    const val TAG_LOGCAT = "logcat"
    const val TAG_CRASH = "CRASH"
    const val TAG_UI = "ui"

    private const val MAX_LINES = 6_000
    private const val MAX_FILE_BYTES = 3L * 1024 * 1024
    private const val MAX_LINES_PER_SECOND = 300

    private val lock = Any()
    private val buffer = ArrayDeque<LogLine>()
    private val seq = AtomicLong(0)
    private val listeners = CopyOnWriteArrayList<(LogLine) -> Unit>()

    // Default filter for the mirrors and the dashboard: INFO and worse. VERBOSE
    // records (the 2-second polls) are still kept in the buffer and in the file,
    // and the debugger's "All" button shows them.
    @Volatile private var minLevel = LogLevel.INFO
    @Volatile private var startedAt = 0L
    @Volatile private var initialised = false

    private var logFile: File? = null
    private var crashFile: File? = null
    private var writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "applog-writer").apply { isDaemon = true }
    }

    // Rate limiting state (guarded by [lock]).
    private var windowStart = 0L
    private var windowCount = 0
    private var droppedInWindow = 0
    @Volatile private var droppedTotal = 0L

    /** Optional extra context printed in the header of every exported report. */
    @Volatile var deviceHeader: String = ""

    // ------------------------------------------------------------------ setup

    /**
     * Points the recorder at `<filesDir>/logs/`. Safe to call repeatedly (the
     * Application and the Service both call it); only the first call wires the
     * files up.
     */
    fun init(context: Context) {
        if (initialised) return
        synchronized(lock) {
            if (initialised) return
            val dir = File(context.filesDir, "logs")
            dir.mkdirs()
            logFile = File(dir, "applog.txt")
            crashFile = File(dir, "last_crash.txt")
            initialised = true
        }
        startedAt = SystemClock.elapsedRealtime()
        i(TAG_SERVICE, "AppLog started (buffer $MAX_LINES lines, file ${logFile?.absolutePath})")
    }

    fun isInitialised(): Boolean = initialised

    fun logFile(): File? = logFile

    fun crashFile(): File? = crashFile

    fun setMinLevel(level: LogLevel) {
        minLevel = level
    }

    fun minLevel(): LogLevel = minLevel

    // ------------------------------------------------------------------ record

    fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null) {
        val text = if (error == null) message else message + "\n" + stackTrace(error)
        val now = System.currentTimeMillis()
        val elapsed = if (startedAt == 0L) 0L else (SystemClock.elapsedRealtime() - startedAt) / 1000
        val thread = Thread.currentThread().name

        val out = ArrayList<LogLine>(2)
        synchronized(lock) {
            // One-second window. Beyond the cap we drop lines and *say so*,
            // because that note matters more than the 4000th logcat line.
            if (now - windowStart > 1000) {
                if (droppedInWindow > 0) {
                    droppedTotal += droppedInWindow
                    out.add(
                        newLine(now, elapsed, LogLevel.WARN, TAG_SERVICE, thread,
                            "log rate limit: dropped $droppedInWindow line(s) in the last second " +
                                "($droppedTotal total this run)")
                    )
                    droppedInWindow = 0
                }
                windowStart = now
                windowCount = 0
            }
            windowCount++
            if (windowCount > MAX_LINES_PER_SECOND) {
                droppedInWindow++
                return
            }
            out.add(newLine(now, elapsed, level, tag, thread, text))
            for (line in out) buffer.addLast(line)
            while (buffer.size > MAX_LINES) buffer.removeFirst()
        }

        for (line in out) {
            if (line.level.priority >= minLevel.priority) {
                mirrorToLogcat(line.level, line.tag, line.message)
            }
            notifyListeners(line)
            persist(line)
        }
    }

    private fun newLine(
        now: Long,
        elapsed: Long,
        level: LogLevel,
        tag: String,
        thread: String,
        message: String
    ) = LogLine(
        seq = seq.incrementAndGet(),
        wallMillis = now,
        elapsedSeconds = elapsed,
        level = level,
        tag = tag,
        thread = thread,
        message = message
    )

    fun v(tag: String, message: String) = log(LogLevel.VERBOSE, tag, message)
    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String) = log(LogLevel.WARN, tag, message)
    fun e(tag: String, message: String, error: Throwable? = null) =
        log(LogLevel.ERROR, tag, message, error)

    /** Never throws: a logging failure must not take the gateway down with it. */
    private fun mirrorToLogcat(level: LogLevel, tag: String, text: String) {
        try {
            when (level) {
                LogLevel.VERBOSE -> android.util.Log.v(tag, text)
                LogLevel.DEBUG -> android.util.Log.d(tag, text)
                LogLevel.INFO -> android.util.Log.i(tag, text)
                LogLevel.WARN -> android.util.Log.w(tag, text)
                LogLevel.ERROR -> android.util.Log.e(tag, text)
            }
        } catch (ignored: Throwable) {
            // logd unavailable (or a unit test) - the buffer and file still have it
        }
    }

    private fun notifyListeners(line: LogLine) {
        for (listener in listeners) {
            try {
                listener(line)
            } catch (ignored: Throwable) {
                // a broken UI listener must not stop recording
            }
        }
    }

    private fun persist(line: LogLine) {
        val file = logFile ?: return
        val text = LogFormat.line(line) + "\n"
        try {
            writer.execute {
                try {
                    if (file.length() > MAX_FILE_BYTES) rotate(file)
                    file.appendText(text)
                } catch (ignored: Throwable) {
                    // disk full / no permission: keep going in memory
                }
            }
        } catch (ignored: Throwable) {
            // executor shut down
        }
    }

    private fun rotate(file: File) {
        val old = File(file.absolutePath + ".1")
        if (old.exists()) old.delete()
        file.renameTo(old)
    }

    /** Blocks (briefly) until queued file writes have landed. Used before export. */
    fun flush(timeoutMs: Long = 1_500) {
        try {
            val marker = java.util.concurrent.CountDownLatch(1)
            writer.execute { marker.countDown() }
            marker.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (ignored: Throwable) {
        }
    }

    // ------------------------------------------------------------------ read

    /** Newest last. `limit` counts back from the newest record. */
    fun snapshot(limit: Int = MAX_LINES, min: LogLevel = LogLevel.VERBOSE): List<LogLine> =
        synchronized(lock) {
            buffer.filter { it.level.priority >= min.priority }
                .let { if (it.size > limit) it.takeLast(limit) else it }
        }

    /** Copyable plain text of the in-memory buffer. */
    fun text(limit: Int = MAX_LINES, min: LogLevel = LogLevel.VERBOSE, compact: Boolean = false): String =
        LogFormat.lines(snapshot(limit, min), compact)

    /** The whole persisted file (survives process death; the buffer does not). */
    fun fileText(maxChars: Int = 400_000): String {
        flush()
        val file = logFile ?: return "(log file not initialised)"
        return try {
            val all = file.readText()
            if (all.length <= maxChars) all
            else "...(older lines trimmed)...\n" + all.takeLast(maxChars)
        } catch (e: Exception) {
            "(could not read ${file.absolutePath}: ${e.message})"
        }
    }

    fun counts(): Map<LogLevel, Int> = synchronized(lock) {
        val map = LinkedHashMap<LogLevel, Int>()
        for (level in LogLevel.values()) map[level] = 0
        for (line in buffer) map[line.level] = (map[line.level] ?: 0) + 1
        map
    }

    fun size(): Int = synchronized(lock) { buffer.size }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            windowCount = 0
            droppedInWindow = 0
        }
        try {
            logFile?.let { f -> writer.execute { f.writeText("") } }
        } catch (ignored: Throwable) {
        }
        i(TAG_SERVICE, "log cleared")
    }

    fun addListener(listener: (LogLine) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (LogLine) -> Unit) {
        listeners.remove(listener)
    }

    // ------------------------------------------------------------------ crash file

    /** Written synchronously by [CrashGuard] - the process is about to die. */
    fun writeCrashFile(text: String) {
        try {
            crashFile?.writeText(text)
        } catch (ignored: Throwable) {
        }
    }

    fun readCrashFile(): String? = try {
        crashFile?.takeIf { it.exists() && it.length() > 0 }?.readText()
    } catch (e: Exception) {
        null
    }

    fun clearCrashFile() {
        try {
            crashFile?.delete()
        } catch (ignored: Throwable) {
        }
    }

    fun stackTrace(t: Throwable): String {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString().trimEnd()
    }
}
