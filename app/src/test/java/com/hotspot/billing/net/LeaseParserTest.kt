package com.hotspot.billing.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LeaseParserTest {

    @Test
    fun `parses a normal lease line`() {
        val leases = LeaseParser.parse(
            listOf("1697000000 aa:bb:cc:dd:ee:ff 10.66.0.12 android-4f2a19 phone-1234")
        )
        assertEquals(1, leases.size)
        assertEquals("aa:bb:cc:dd:ee:ff", leases[0].mac)
        assertEquals("10.66.0.12", leases[0].ip)
        assertEquals("android-4f2a19", leases[0].hostname)
    }

    @Test
    fun `star hostname becomes empty`() {
        val leases = LeaseParser.parse(listOf("1697000000 11:22:33:44:55:66 10.66.0.13 *"))
        assertEquals("", leases[0].hostname)
    }

    @Test
    fun `junk and blank lines are skipped`() {
        val leases = LeaseParser.parse(
            listOf(
                "",
                "duid time 1697000000",
                "header line that is not a lease at all",
                "1697000000 aa:bb:cc:dd:ee:ff 10.66.0.12 host clientid"
            )
        )
        assertEquals(1, leases.size)
        assertEquals("aa:bb:cc:dd:ee:ff", leases[0].mac)
    }

    @Test
    fun `mac is lowercased`() {
        val leases = LeaseParser.parse(listOf("1697000000 AA:BB:CC:DD:EE:FF 10.66.0.12 host x"))
        assertEquals("aa:bb:cc:dd:ee:ff", leases[0].mac)
    }

    @Test
    fun `all-zero mac is rejected`() {
        assertTrue(LeaseParser.parse(listOf("1697000000 00:00:00:00:00:00 10.66.0.12 host x")).isEmpty())
    }
}
