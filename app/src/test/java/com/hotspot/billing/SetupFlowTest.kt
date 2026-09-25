package com.hotspot.billing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The wizard's input validation is the first line of defence against the
 * F-01 class of bug (operator text reaching a uid-0 shell command), so the
 * rules are pinned down here: what passes, and exactly what is rejected.
 */
class SetupFlowTest {

    // ------------------------------------------------------------------ SSID

    @Test
    fun `a normal SSID passes`() {
        assertNull(SetupFlow.validateSsid("RNS-Hotspot"))
        assertNull(SetupFlow.validateSsid("My Cafe 02"))
        assertNull(SetupFlow.validateSsid("a"))
        assertNull(SetupFlow.validateSsid("x".repeat(32)))
    }

    @Test
    fun `the SSID limits are enforced`() {
        assertNotNull(SetupFlow.validateSsid(""))
        assertNotNull(SetupFlow.validateSsid("   "))
        assertNotNull(SetupFlow.validateSsid("x".repeat(33)))
        assertNotNull(SetupFlow.validateSsid("café"))
    }

    @Test
    fun `shell-dangerous characters are rejected in the SSID`() {
        // F-01: any of these in the SSID could break out of the quoted root
        // shell word and run a command substitution as uid 0. (A bare `;`
        // inside the double quotes is a literal, so it is allowed.)
        for (evil in listOf("$(id)", "`id`", "a\"b", "a\\b")) {
            assertNotNull("should reject: $evil", SetupFlow.validateSsid(evil))
        }
    }

    // ------------------------------------------------------------- passphrase

    @Test
    fun `a normal passphrase passes`() {
        assertNull(SetupFlow.validatePassphrase("hotspot123"))
        assertNull(SetupFlow.validatePassphrase("a".repeat(8)))
        assertNull(SetupFlow.validatePassphrase("b".repeat(63)))
    }

    @Test
    fun `the passphrase limits are enforced`() {
        assertNotNull(SetupFlow.validatePassphrase(""))
        assertNotNull(SetupFlow.validatePassphrase("short"))
        assertNotNull(SetupFlow.validatePassphrase("x".repeat(64)))
        assertNotNull(SetupFlow.validatePassphrase("parolè"))
    }

    @Test
    fun `shell-dangerous characters are rejected in the passphrase`() {
        for (evil in listOf("$(reboot)", "`reboot`", "p\"w", "p\\w", "p`w")) {
            assertNotNull("should reject: $evil", SetupFlow.validatePassphrase(evil))
        }
    }

    // ---------------------------------------------------------------- presets

    @Test
    fun `unknown preset labels fall back to the first preset`() {
        assertEquals("1 Hour", VoucherPresets.from(null).label)
        assertEquals("1 Hour", VoucherPresets.from("Bogus").label)
        assertEquals(1440, VoucherPresets.from("1 Day").minutes)
    }

    @Test
    fun `the plan name is the stored voucher label`() {
        val preset = VoucherPresets.from("3 Hours")
        assertEquals("3 Hours · 2/1 Mbps", VoucherPresets.planName(preset, 2, 1))
    }
}
