package com.hotspot.billing.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApConfigTextTest {

    @Test
    fun `reads the SSID and passphrase out of the system hostapd conf`() {
        val config = ApConfigText.parseHostapd(
            """
            # Android wrote this
            interface=ap0
            driver=nl80211
            ctrl_interface=/data/vendor/wifi/hostapd/sockets
            ssid=AndroidShare_3018
            hw_mode=g
            channel=6
            wpa=2
            wpa_passphrase=abc123def
            ignore_broadcast_ssid=0
            """.trimIndent()
        )
        assertEquals("ap0", config!!.interfaceName)
        assertEquals("AndroidShare_3018", config.ssid)
        assertEquals("abc123def", config.passphrase)
        assertEquals(6, config.channel)
    }

    @Test
    fun `a quoted SSID keeps its quotes - the caller strips them`() {
        val config = ApConfigText.parseHostapd("ssid=\"My AP\"\nwpa_passphrase=secret123\n")
        assertEquals("\"My AP\"", config!!.ssid)
    }

    @Test
    fun `garbage and comments are ignored instead of throwing`() {
        assertNull(ApConfigText.parseHostapd(""))
        assertNull(ApConfigText.parseHostapd("# only a comment\nnot a key value pair\n"))
        assertEquals("x", ApConfigText.parseHostapd("=broken\nssid=x\n")!!.ssid)
    }

    @Test
    fun `a WiFi Direct name is forced into the DIRECT-xy shape the framework demands`() {
        assertEquals("DIRECT-RNS", ApConfigText.normalizeDirectSsid("RNS"))
        assertEquals("DIRECT-RNSHotspot", ApConfigText.normalizeDirectSsid("RNS-Hotspot"))
        assertEquals("DIRECT-abc", ApConfigText.normalizeDirectSsid("DIRECT-abc"))
        // Spaces and quotes would break the request entirely.
        assertEquals("DIRECT-MyAP", ApConfigText.normalizeDirectSsid("My AP!"))
        assertEquals("DIRECT-RNS", ApConfigText.normalizeDirectSsid(null))
        // The prefix rule is ^DIRECT-[a-zA-Z0-9]{2} - a single char is padded.
        assertEquals("DIRECT-aX", ApConfigText.normalizeDirectSsid("a"))
    }

    @Test
    fun `passphrases are pushed into the 8-63 ASCII range WPA2 requires`() {
        assertEquals("hotspot123", ApConfigText.sanitizePassphrase("hotspot123"))
        assertEquals("short120", ApConfigText.sanitizePassphrase("short12"))  // 7 chars -> padded to 8
        assertEquals("hotspot123", ApConfigText.sanitizePassphrase(""))
        assertEquals("hotspot123", ApConfigText.sanitizePassphrase(null))
        assertEquals(63, ApConfigText.sanitizePassphrase("x".repeat(90)).length)
    }

    @Test
    fun `the new AP interface is the one that was not there before`() {
        val before = setOf("lo", "wlan0", "ccmni0")
        val after = listOf(
            "lo" to true, "wlan0" to true, "ccmni0" to true, "p2p-wlan0-0" to true
        )
        assertEquals("p2p-wlan0-0", ApConfigText.pickApInterface(before, after, "ccmni0"))
    }

    @Test
    fun `a well-known AP name wins over an unknown new interface`() {
        val before = setOf("lo", "wlan0")
        val after = listOf("lo" to true, "wlan0" to true, "dummy0" to true, "ap0" to true)
        assertEquals("ap0", ApConfigText.pickApInterface(before, after, "wlan0"))
    }

    @Test
    fun `the internet side is never mistaken for the customer side`() {
        val before = setOf("lo")
        val after = listOf("lo" to true, "wlan0" to true)
        assertNull(ApConfigText.pickApInterface(before, after, "wlan0"))
    }

    @Test
    fun `a DOWN interface is not an AP`() {
        val before = setOf("lo")
        val after = listOf("lo" to true, "ap0" to false)
        assertNull(ApConfigText.pickApInterface(before, after, null))
    }

    @Test
    fun `when nothing new appeared, a known AP interface that is already up is adopted`() {
        val before = setOf("lo", "wlan0", "ap0")
        val after = listOf("lo" to true, "wlan0" to true, "ap0" to true)
        assertEquals("ap0", ApConfigText.pickApInterface(before, after, "wlan0"))
    }

    @Test
    fun `p2p interface names are recognised`() {
        assertTrue(ApConfigText.looksLikeP2pInterface("p2p0"))
        assertTrue(ApConfigText.looksLikeP2pInterface("p2p-wlan0-0"))
        assertFalse(ApConfigText.looksLikeP2pInterface("wlan0"))
    }

    @Test
    fun `the netshare runtime file parses into a map`() {
        val map = ApConfigText.parseKeyValue(
            """
            IFACE=rnsap0
            SSID=RNS-Hotspot
            PASS=hotspot123
            CHANNEL=6
            MODE=root-hostapd
            CREATED=1
            AP_IP=192.168.50.1/24
            """.trimIndent()
        )
        assertEquals("rnsap0", map["IFACE"])
        assertEquals("root-hostapd", map["MODE"])
        assertEquals("1", map["CREATED"])
        assertEquals("192.168.50.1/24", map["AP_IP"])
    }
}
