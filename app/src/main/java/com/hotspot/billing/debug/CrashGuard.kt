package com.hotspot.billing.debug

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Catches everything that would otherwise die silently:
 *
 *  - uncaught exceptions on any thread (crash the app used to just vanish with
 *    no trace, because the only log was logcat and nobody was reading it);
 *  - coroutine failures inside the service scope, which a `SupervisorJob`
 *    swallows without cancelling anything;
 *  - `Thread.UncaughtExceptionHandler` chains, so the OS still gets the crash
 *    (dialog / restart behaviour is unchanged).
 *
 * Each crash is written to `files/logs/last_crash.txt` **together with the last
 * [TAIL_LINES] log records**, because the interesting part is never the stack
 * trace alone - it is what the gateway did in the two seconds before it.
 */
object CrashGuard {

    private const val TAIL_LINES = 120

    @Volatile private var installed = false
    @Volatile var lastCrashAt: Long = 0
        private set

    private var previousHandler: Thread.UncaughtExceptionHandler? = null
    private var context: Context? = null
    private var versionLabel: String = "?"

    @Synchronized
    fun install(context: Context, versionLabel: String) {
        if (installed) return
        this.context = context.applicationContext
        this.versionLabel = versionLabel
        AppLog.init(context.applicationContext)
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            record(thread.name, throwable)
            // Hand over to the platform handler (or the runtime kills nothing).
            val prev = previousHandler
            if (prev != null) {
                prev.uncaughtException(thread, throwable)
            } else {
                // No previous handler: kill the process the way the runtime would.
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
        installed = true

        // A crash from a previous run is the first thing worth showing.
        val previous = AppLog.readCrashFile()
        if (previous != null) {
            AppLog.e(
                AppLog.TAG_CRASH,
                "previous run crashed - full trace in the debugger (last_crash.txt)"
            )
        }
    }

    fun isInstalled(): Boolean = installed

    /** Handler for `CoroutineScope(SupervisorJob() + Dispatchers.IO + CrashGuard.handler())`. */
    fun handler(): CoroutineExceptionHandler = CoroutineExceptionHandler { _, throwable ->
        record("coroutine", throwable)
    }

    /** Records without killing anything: used for "should never happen" paths. */
    fun record(where: String, throwable: Throwable) {
        lastCrashAt = System.currentTimeMillis()
        val text = describe(where, throwable)
        AppLog.e(AppLog.TAG_CRASH, text)
        AppLog.writeCrashFile(text)
    }

    fun describe(where: String, throwable: Throwable): String = buildString {
        append("=== CRASH / UNCAUGHT ERROR ===\n")
        append("at          : ").append(LogFormat.timestamp(System.currentTimeMillis())).append('\n')
        append("where       : ").append(where).append('\n')
        append("app         : ").append(versionLabel).append('\n')
        append("device      : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" / Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(')')
            .append(" / ").append(Build.DISPLAY).append('\n')
        append("exception   : ").append(throwable.javaClass.name)
            .append(": ").append(throwable.message).append('\n')
        append("--- stack trace ---\n")
        append(AppLog.stackTrace(throwable)).append('\n')
        append("--- last $TAIL_LINES log records before the crash ---\n")
        append(AppLog.text(TAIL_LINES))
    }

    fun lastCrashText(): String? = AppLog.readCrashFile()

    fun clearLastCrash() {
        AppLog.clearCrashFile()
        AppLog.i(AppLog.TAG_CRASH, "saved crash report cleared")
    }

    fun describePrevious(context: Context): String {
        val crash = lastCrashText() ?: return "(no saved crash report)"
        return crash
    }
}
