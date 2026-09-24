package com.hotspot.billing.debug

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Severity of one log record. Ordered, so a UI filter is just `>= minLevel`. */
enum class LogLevel(val letter: Char, val priority: Int) {
    VERBOSE('V', 2),
    DEBUG('D', 3),
    INFO('I', 4),
    WARN('W', 5),
    ERROR('E', 6);

    companion object {
        fun fromLetter(c: Char): LogLevel? = values().firstOrNull { it.letter == c }
    }
}

/**
 * One recorded event. `elapsedSeconds` is seconds since boot, which is what
 * makes "the AP came up, then 4 s later dnsmasq died" readable in a pasted log
 * even when the wall clock jumps (NTP sync mid-session).
 */
data class LogLine(
    val seq: Long,
    val wallMillis: Long,
    val elapsedSeconds: Long,
    val level: LogLevel,
    val tag: String,
    val thread: String,
    val message: String
)

/**
 * Renders log lines as plain text. Pure JVM (no Android types) so the exact
 * copyable format is unit-tested.
 *
 * Continuation lines of a multi-line message are indented and prefixed with
 * `|` so a pasted report stays readable and each record still starts with a
 * timestamp.
 */
object LogFormat {

    private const val MAX_MESSAGE_CHARS = 8_000

    // SimpleDateFormat is not thread-safe; every format goes through the lock.
    private val full = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val clock = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun timestamp(millis: Long): String = synchronized(this) { full.format(Date(millis)) }

    fun clock(millis: Long): String = synchronized(this) { clock.format(Date(millis)) }

    /** One record, ready to paste: `2026-09-23 21:04:11.234 +123s D/Root[main]: ...`. */
    fun line(l: LogLine): String = buildString {
        append(timestamp(l.wallMillis))
        append(" +").append(l.elapsedSeconds).append('s')
        append(' ').append(l.level.letter).append('/').append(l.tag)
        append('[').append(l.thread).append("] ")
        append(indent(l.message))
    }

    /** Compact form for on-screen tailing (no date, less noise). */
    fun shortLine(l: LogLine): String = buildString {
        append(clock(l.wallMillis))
        append(' ').append(l.level.letter).append('/').append(l.tag).append(' ')
        append(indent(l.message))
    }

    /** Keeps the first [MAX_MESSAGE_CHARS] chars and indents continuation lines. */
    fun indent(message: String): String {
        val clipped = if (message.length > MAX_MESSAGE_CHARS) {
            message.substring(0, MAX_MESSAGE_CHARS) +
                "\n... (truncated ${message.length - MAX_MESSAGE_CHARS} chars)"
        } else {
            message
        }
        if (!clipped.contains('\n')) return clipped
        return clipped.lineSequence().mapIndexed { index, line ->
            if (index == 0) line else "    | $line"
        }.joinToString("\n")
    }

    fun lines(lines: List<LogLine>, compact: Boolean = false): String =
        if (lines.isEmpty()) "(empty)"
        else lines.joinToString("\n") { if (compact) shortLine(it) else line(it) }
}
