package com.hotspot.billing.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JoinConfigTest {

    @Test
    fun `the fixed passphrase is a valid WPA2-PSK (8..63 printable ASCII)`() {
        val pass = JoinConfig.FIXED_PASSPHRASE
        assertTrue("length ${pass.length} outside 8..63", pass.length in 8..63)
        assertTrue(pass.all { it in 0x20..0x7E })
    }

    @Test
    fun `the QR payload is the standard WIFI format with our passphrase`() {
        val payload = JoinConfig.wifiQrPayload("DIRECT-RNSHotspot")
        assertTrue(payload.startsWith("WIFI:T:WPA;"))
        assertTrue(payload.contains("S:DIRECT-RNSHotspot;"))
        assertTrue(payload.contains("P:${JoinConfig.FIXED_PASSPHRASE};"))
        assertTrue(payload.endsWith(";;"))
    }

    @Test
    fun `special characters in the SSID are escaped for the QR spec`() {
        val payload = JoinConfig.wifiQrPayload("DIRECT-a;b:c,d\\e")
        assertTrue(payload.contains("S:DIRECT-a\\;b\\:c\\,d\\\\e;"))
    }

    @Test
    fun `the requested SSID always carries the DIRECT- prefix`() {
        assertEquals("DIRECT-RNSHotspot", JoinConfig.requestedSsid("RNS-Hotspot"))
        assertEquals("DIRECT-RNS", JoinConfig.requestedSsid(null))
        assertTrue(JoinConfig.requestedSsid("whatever").matches(Regex("^DIRECT-[a-zA-Z0-9]{2,17}$")))
    }
}
