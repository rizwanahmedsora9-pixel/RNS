package com.hotspot.billing.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {

    @Test
    fun `lines keep their insertion order and carry level, tag and thread`() {
        AppLog.clear()
        AppLog.i("gateway", "info line")
        AppLog.w("watchdog", "warn line")
        AppLog.e("root", "error line")

        val all = AppLog.snapshot()
        assertEquals(4, all.size) // clear() itself records "log cleared"
        assertEquals("info line", all[1].message)
        assertEquals("warn line", all[2].message)
        assertEquals(LogLevel.ERROR, all[3].level)
        assertEquals("root", all[3].tag)
        assertTrue(all[3].thread.isNotBlank())
        assertTrue(all[1].seq < all[2].seq)
    }

    @Test
    fun `a snapshot can be filtered by level - that is the dashboard view`() {
        AppLog.clear()
        AppLog.i("t", "i")
        AppLog.w("t", "w")
        AppLog.e("t", "e")
        AppLog.d("t", "d")

        val warnUp = AppLog.snapshot(min = LogLevel.WARN)
        assertEquals(2, warnUp.size)
        assertTrue(warnUp.all { it.level.priority >= LogLevel.WARN.priority })
        assertEquals(5, AppLog.snapshot().size)
        // limit counts back from the newest record
        assertEquals(listOf("e"), AppLog.snapshot(limit = 1, min = LogLevel.ERROR).map { it.message })
    }

    @Test
    fun `verbose records are kept even though the default mirror level is INFO`() {
        AppLog.clear()
        AppLog.v("t", "poll tick")
        assertTrue(AppLog.snapshot().any { it.message == "poll tick" })
        assertTrue(AppLog.snapshot(min = LogLevel.INFO).none { it.message == "poll tick" })
    }

    @Test
    fun `the mirror level is read back after being changed`() {
        val previous = AppLog.minLevel()
        try {
            AppLog.setMinLevel(LogLevel.ERROR)
            assertEquals(LogLevel.ERROR, AppLog.minLevel())
            // Raising the mirror level must never drop records from the buffer.
            AppLog.clear()
            AppLog.i("t", "still recorded")
            assertTrue(AppLog.snapshot().any { it.message == "still recorded" })
        } finally {
            AppLog.setMinLevel(previous)
        }
    }

    @Test
    fun `listeners see every record - that is how the debugger screen tails the log`() {
        AppLog.clear()
        val seen = mutableListOf<LogLine>()
        val listener: (LogLine) -> Unit = { seen += it }
        AppLog.addListener(listener)
        try {
            AppLog.i("t", "one")
            AppLog.e("t", "two")
        } finally {
            AppLog.removeListener(listener)
        }
        assertEquals(listOf("one", "two"), seen.map { it.message })

        AppLog.i("t", "three")
        assertFalse(seen.any { it.message == "three" })
    }

    @Test
    fun `text renders the records in the format the operator pastes back`() {
        AppLog.clear()
        AppLog.w("watchdog", "H3 no DHCP")
        val text = AppLog.text()
        assertTrue(text, text.contains("W/watchdog"))
        assertTrue(text, text.contains("H3 no DHCP"))

        assertEquals("(empty)", AppLog.text(limit = 0))
        // The compact form (the on-screen tail) has no date column.
        assertFalse(AppLog.text(compact = true).substringAfter('W').contains("-"))
    }

    @Test
    fun `an exception is recorded with its stack trace attached to the message`() {
        AppLog.clear()
        AppLog.e("root", "hostapd failed", IllegalStateException("no carrier"))
        val line = AppLog.snapshot(min = LogLevel.ERROR).last()
        assertTrue(line.message.startsWith("hostapd failed"))
        assertTrue(line.message.contains("IllegalStateException"))
        assertTrue(line.message.contains("no carrier"))
    }
}
