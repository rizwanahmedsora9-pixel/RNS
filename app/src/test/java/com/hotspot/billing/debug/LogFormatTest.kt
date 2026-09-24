package com.hotspot.billing.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogFormatTest {

    private fun line(
        message: String,
        level: LogLevel = LogLevel.INFO,
        tag: String = "gateway"
    ) = LogLine(
        seq = 1,
        wallMillis = 1_758_661_451_234L,
        elapsedSeconds = 42,
        level = level,
        tag = tag,
        thread = "main",
        message = message
    )

    @Test
    fun `a record starts with a timestamp, uptime, level, tag and thread`() {
        val text = LogFormat.line(line("hotspot went off"))
        assertTrue("no date: $text", text.startsWith("20"))
        assertTrue("no uptime: $text", text.contains(" +42s"))
        assertTrue("no level/tag: $text", text.contains(" I/gateway[main] "))
        assertTrue("no message: $text", text.endsWith("hotspot went off"))
    }

    @Test
    fun `the compact form drops the date but keeps the level and tag`() {
        val text = LogFormat.shortLine(line("hello", LogLevel.WARN, "watchdog"))
        assertTrue(text, text.contains(" W/watchdog hello"))
        // A date would be "yyyy-MM-dd" - the compact form must not carry one.
        assertFalse(text, text.contains("-"))
        assertTrue(text, text.contains(":"))
    }

    @Test
    fun `continuation lines are indented so a pasted log stays readable`() {
        val text = LogFormat.line(line("first\nsecond\nthird"))
        val lines = text.split("\n")
        assertEquals(3, lines.size)
        assertTrue(lines[1], lines[1].startsWith("    | second"))
        assertTrue(lines[2], lines[2].startsWith("    | third"))
    }

    @Test
    fun `an enormous message is clipped instead of blowing up the report`() {
        val huge = "x".repeat(20_000)
        val text = LogFormat.line(line(huge))
        assertTrue(text.length < huge.length)
        assertTrue(text.contains("truncated"))
    }

    @Test
    fun `levels are ordered so a filter is a simple comparison`() {
        assertTrue(LogLevel.ERROR.priority > LogLevel.WARN.priority)
        assertTrue(LogLevel.WARN.priority > LogLevel.INFO.priority)
        assertTrue(LogLevel.INFO.priority > LogLevel.DEBUG.priority)
        assertEquals(LogLevel.WARN, LogLevel.fromLetter('W'))
        assertNull(LogLevel.fromLetter('Q'))
    }

    @Test
    fun `an empty log renders as a placeholder, not a blank screen`() {
        assertEquals("(empty)", LogFormat.lines(emptyList()))
    }
}
