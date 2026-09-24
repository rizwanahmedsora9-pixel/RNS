package com.hotspot.billing.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogcatWatcherTest {

    @Test
    fun `threadtime lines are parsed into a level and a tag`() {
        val parsed = LogcatWatcher.parseThreadtime(
            "09-23 21:04:11.234  1234  1234 D wifi    : Connected to ap0"
        )
        assertEquals(LogLevel.DEBUG, parsed!!.first)
        assertEquals("wifi", parsed.second)
    }

    @Test
    fun `a tag that is glued to its colon still parses`() {
        val parsed = LogcatWatcher.parseThreadtime("09-23 21:04:11.234 1 2 E Tethering: failed")
        assertEquals(LogLevel.ERROR, parsed!!.first)
        assertEquals("Tethering", parsed.second)
    }

    @Test
    fun `lines that are not threadtime are ignored rather than mislabelled`() {
        assertNull(LogcatWatcher.parseThreadtime("--------- beginning of main"))
        assertNull(LogcatWatcher.parseThreadtime(""))
    }

    @Test
    fun `the rooted command filters to the networking tags and silences the rest`() {
        val command = LogcatWatcher.buildCommand(LogcatWatcher.Mode.FILTERED, root = true)
        assertTrue(command, command.startsWith("su -c \"logcat"))
        assertTrue(command, command.contains("wpa_supplicant:V"))
        assertTrue(command, command.contains("WifiP2pService:V"))
        assertTrue(command, command.contains("Tethering:V"))
        assertTrue(command, command.contains("*:S"))
        assertTrue(command, command.contains("-b crash"))
    }

    @Test
    fun `the everything mode has no tag filter at all`() {
        val command = LogcatWatcher.buildCommand(LogcatWatcher.Mode.EVERYTHING, root = true)
        assertTrue(command, !command.contains("*:S"))
        assertTrue(command, command.contains("-v threadtime"))
    }

    @Test
    fun `quoted commands are split into shell words, keeping su -c as one argument`() {
        val parts = LogcatWatcher.splitCommand("su -c \"logcat -v threadtime *:S\"")
        assertEquals(3, parts.size)
        assertEquals("su", parts[0])
        assertEquals("-c", parts[1])
        assertEquals("logcat -v threadtime *:S", parts[2])
    }

    @Test
    fun `the mode is read back from preferences by key`() {
        assertEquals(LogcatWatcher.Mode.EVERYTHING, LogcatWatcher.Mode.from("all"))
        assertEquals(LogcatWatcher.Mode.OFF, LogcatWatcher.Mode.from("off"))
        // An unknown or missing value must not leave the watcher off silently.
        assertEquals(LogcatWatcher.Mode.FILTERED, LogcatWatcher.Mode.from(null))
        assertEquals(LogcatWatcher.Mode.FILTERED, LogcatWatcher.Mode.from("nonsense"))
    }
}
