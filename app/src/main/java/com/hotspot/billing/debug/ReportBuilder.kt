package com.hotspot.billing.debug

/**
 * Builds the plain-text debug report the operator copies and pastes.
 *
 * Pure JVM (no Android, no shell) so the exact layout - headers, command
 * results with exit codes and timings, truncation - is unit-tested.
 *
 * Layout rules, because the report is read by a human and by whoever debugs it:
 *  - `########` banner, `== N. SECTION ==`, `-- command (exit N, Nms) --`
 *  - every command result is prefixed with its exit code and duration, so a
 *    silent failure is visible instead of looking like empty output
 *  - output is capped per command and overall; a 4 MB paste is useless
 */
class ReportBuilder(private val title: String) {

    private val sb = StringBuilder()
    private var sectionIndex = 0
    private var commands = 0

    /** `key : value` lines under the banner. */
    fun header(pairs: List<Pair<String, Any?>>): ReportBuilder {
        sb.append(BANNER).append('\n')
        sb.append(title).append('\n')
        sb.append(BANNER).append('\n')
        for ((k, v) in pairs) kv(k, v)
        sb.append('\n')
        return this
    }

    fun section(name: String): ReportBuilder {
        sectionIndex++
        sb.append("\n== ").append(sectionIndex).append(". ").append(name).append(" ==\n")
        return this
    }

    fun line(text: String): ReportBuilder {
        sb.append(text).append('\n')
        return this
    }

    fun kv(key: String, value: Any?): ReportBuilder {
        sb.append(key.padEnd(KEY_WIDTH)).append(": ")
            .append(value?.toString()?.ifBlank { "(blank)" } ?: "(null)").append('\n')
        return this
    }

    /** A labelled, indented block of already-formatted text. */
    fun block(label: String, content: String, maxChars: Int = MAX_BLOCK_CHARS): ReportBuilder {
        sb.append("-- ").append(label).append(" --\n")
        val text = content.trimEnd()
        if (text.isEmpty()) {
            sb.append("    (no output)\n")
            return this
        }
        val clipped = if (text.length > maxChars) {
            text.take(maxChars) + "\n... (truncated ${text.length - maxChars} chars)"
        } else {
            text
        }
        for (line in clipped.lineSequence()) {
            sb.append("    ").append(line).append('\n')
        }
        return this
    }

    /** Result of one shell command, with its exit code and duration. */
    fun command(
        label: String,
        exit: Int,
        millis: Long,
        out: String,
        err: String = ""
    ): ReportBuilder {
        commands++
        sb.append("-- ").append(label)
            .append(" (exit ").append(exit).append(", ").append(millis).append("ms) --\n")
        val body = buildString {
            append(out.trimEnd())
            if (err.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append("stderr: ").append(err.trimEnd())
            }
        }
        if (body.isBlank()) {
            sb.append("    (no output)\n")
        } else {
            val text = if (body.length > MAX_BLOCK_CHARS) {
                body.take(MAX_BLOCK_CHARS) + "\n... (truncated ${body.length - MAX_BLOCK_CHARS} chars)"
            } else {
                body
            }
            for (line in text.lineSequence()) {
                sb.append("    ").append(line).append('\n')
            }
        }
        return this
    }

    fun note(text: String): ReportBuilder {
        sb.append("NOTE: ").append(text).append('\n')
        return this
    }

    fun build(): String {
        sb.append("\n== end of report (").append(sectionIndex).append(" sections, ")
            .append(commands).append(" commands) ==\n")
        return sb.toString()
    }

    fun length(): Int = sb.length

    companion object {
        private const val BANNER = "############################################"
        private const val KEY_WIDTH = 18
        const val MAX_BLOCK_CHARS = 12_000
    }
}
