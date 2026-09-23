package com.hotspot.billing.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoucherCodesTest {

    @Test
    fun `generated codes match the printed format`() {
        repeat(500) {
            val code = VoucherCodes.generate()
            assertTrue("bad code: $code", Regex(VoucherCodes.FORMAT_REGEX).matches(code))
        }
    }

    @Test
    fun `generated codes never contain ambiguous characters`() {
        repeat(500) {
            val code = VoucherCodes.generate()
            assertFalse("0 in $code", code.contains('0'))
            assertFalse("O in $code", code.contains('O'))
            assertFalse("1 in $code", code.contains('1'))
            assertFalse("I in $code", code.contains('I'))
        }
    }

    @Test
    fun `normalize uppercases and reinserts a missing dash`() {
        assertEquals("AB12-CD34", VoucherCodes.normalize("  ab12cd34 "))
        assertEquals("AB12-CD34", VoucherCodes.normalize("ab12-cd34"))
        assertEquals("AB12-CD34", VoucherCodes.normalize("AB12 CD34"))
    }

    @Test
    fun `normalize leaves incomplete input alone instead of inventing a dash`() {
        assertEquals("AB12", VoucherCodes.normalize("ab12"))
        assertEquals("", VoucherCodes.normalize("   "))
    }
}
