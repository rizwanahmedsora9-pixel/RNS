package com.hotspot.billing.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that the 2026-09-24 14:42 device log broke, pinned down.
 *
 * That session "adopted" p2p0 because `ip link` said UP, then assigned
 * 10.66.0.1/24 to it, started dnsmasq on it and reported a running hotspot -
 * while nothing was beaconing. Every test below is a state the phone really
 * gets into.
 */
class ApEvidenceTest {

    private fun iface(
        name: String,
        up: Boolean = true,
        carrier: Boolean = true,
        addresses: List<String> = emptyList()
    ) = RadioSnapshot.Iface(name, up, carrier, addresses)

    // ------------------------------------------------------------------ the failure

    @Test
    fun `p2p0 is up but has no group - it is not a hotspot`() {
        // Exactly the 14:42 state: WiFi on (so p2p0 exists and is UP), no group
        // running, no address on it, nothing beaconing.
        val snap = RadioSnapshot(
            interfaces = listOf(
                iface("wlan0", up = false),
                iface("p2p0", up = true),
                iface("lo", up = true)
            ),
            defaultRoute = "ccmni0",
            tetherIfaceProp = "ap0",
            p2pIfaceProp = "p2p0",
            hostapdProcess = false,
            hostapdConf = null,
            frameworkApState = 11, // WIFI_AP_STATE_DISABLED
            p2pGroupOwner = false // the framework answered: no group
        )

        val decision = ApEvidence.evaluate(snap, pin = null)

        assertNull("a dead p2p0 must never be adopted", decision.iface)
        assertFalse(decision.isBeaconing)
        assertTrue(
            "the rejection must name the p2p trap",
            decision.rejected.any { it.startsWith("p2p0") && it.contains("WiFi Direct group") }
        )
    }

    @Test
    fun `the address our script wrote is never evidence of a hotspot`() {
        val snap = RadioSnapshot(
            interfaces = listOf(
                iface("p2p0", addresses = listOf("10.66.0.1/24"))
            ),
            defaultRoute = "ccmni0",
            p2pIfaceProp = "p2p0",
            frameworkApState = 11,
            p2pGroupOwner = false
        )

        val decision = ApEvidence.evaluate(snap, pin = null)

        assertNull(decision.iface)
        assertTrue(
            decision.rejected.any { it.contains("p2p0") && it.contains("WiFi Direct group") }
        )
        assertTrue(ApEvidence.isOurOwnAddress("10.66.0.1/24"))
        assertFalse(ApEvidence.isOurOwnAddress("192.168.49.1/24"))
    }

    // ------------------------------------------------------------------ the working state

    @Test
    fun `a WiFi Direct group owner on p2p0 is a real hotspot`() {
        // The 12:37 log: P2P-GROUP-STARTED, p2p0 carries 192.168.49.1/24.
        val snap = RadioSnapshot(
            interfaces = listOf(
                iface("wlan0"),
                iface("p2p0", addresses = listOf("192.168.49.1/24")),
                iface("lo")
            ),
            defaultRoute = "ccmni0",
            p2pIfaceProp = "p2p0",
            tetherIfaceProp = "ap0",
            frameworkApState = 11, // the softap itself is off - this is P2P
            p2pGroupOwner = null   // requestGroupInfo did not answer
        )

        val decision = ApEvidence.evaluate(snap, pin = null)

        assertEquals("p2p0", decision.iface)
        assertEquals(ApKind.WIFI_DIRECT, decision.kind)
        assertTrue(decision.proof.contains("group-owner address"))
    }

    @Test
    fun `the framework owning the group is enough even without an address yet`() {
        val snap = RadioSnapshot(
            interfaces = listOf(iface("p2p0")),
            defaultRoute = "ccmni0",
            p2pIfaceProp = "p2p0",
            p2pGroupOwner = true,
            p2pGroupIface = "p2p0",
            p2pGroupSsid = "DIRECT-5O-Infinix HOT 8",
            p2pGroupPassphrase = "kamm4fFC"
        )

        val decision = ApEvidence.evaluate(snap, pin = null)

        assertEquals("p2p0", decision.iface)
        assertEquals(ApKind.WIFI_DIRECT, decision.kind)
        assertEquals("DIRECT-5O-Infinix HOT 8", decision.ssid)
        assertEquals("kamm4fFC", decision.password)
    }

    @Test
    fun `the framework saying no group overrides a leftover group-owner address`() {
        val snap = RadioSnapshot(
            interfaces = listOf(iface("p2p0", addresses = listOf("192.168.49.1/24"))),
            defaultRoute = "ccmni0",
            p2pIfaceProp = "p2p0",
            p2pGroupOwner = false // definitive answer, whatever the address says
        )

        assertNull(ApEvidence.evaluate(snap, pin = null).iface)
    }

    // ------------------------------------------------------------------ system hotspot

    @Test
    fun `ap0 with the framework state ENABLED is the Android hotspot`() {
        val snap = RadioSnapshot(
            interfaces = listOf(iface("ap0", addresses = listOf("192.168.43.1/24"))),
            defaultRoute = "ccmni0",
            tetherIfaceProp = "ap0",
            hostapdProcess = true,
            hostapdConf = ApConfigText.ApConfig(
                interfaceName = "ap0", ssid = "RNS-Hotspot", passphrase = "hotspot123"
            ),
            frameworkApState = ApEvidence.AP_STATE_ENABLED,
            frameworkApSsid = "RNS-Hotspot",
            frameworkApPassword = "hotspot123"
        )

        val decision = ApEvidence.evaluate(snap, pin = null)

        assertEquals("ap0", decision.iface)
        assertEquals(ApKind.SYSTEM_HOTSPOT, decision.kind)
        assertEquals("RNS-Hotspot", decision.ssid)
    }

    @Test
    fun `ap0 with the framework DISABLED and only a leftover address is not an AP`() {
        // The address Android wrote before it stopped the softap is still there;
        // the state machine says the AP is off.
        val snap = RadioSnapshot(
            interfaces = listOf(iface("ap0", addresses = listOf("192.168.43.1/24"))),
            defaultRoute = "ccmni0",
            tetherIfaceProp = "ap0",
            hostapdProcess = false,
            hostapdConf = null,
            frameworkApState = ApEvidence.AP_STATE_DISABLED
        )

        val decision = ApEvidence.evaluate(snap, pin = null)

        assertNull(decision.iface)
        assertTrue(decision.rejected.any { it.contains("DISABLED") })
    }

    @Test
    fun `ENABLING counts while we are waiting for an AP we just requested`() {
        val snap = RadioSnapshot(
            interfaces = listOf(iface("ap0", addresses = listOf("192.168.43.1/24"))),
            defaultRoute = "ccmni0",
            tetherIfaceProp = "ap0",
            frameworkApState = ApEvidence.AP_STATE_ENABLING
        )

        assertNull("an AP that is only ENABLING is not adopted", ApEvidence.evaluate(snap, null).iface)
        assertEquals(
            "ap0",
            ApEvidence.evaluate(snap, pin = null, expectStart = true).iface
        )
    }

    // ------------------------------------------------------------------ ordering / pins

    @Test
    fun `the internet side is never adopted as the LAN`() {
        val snap = RadioSnapshot(
            // ccmni0 is a mobile uplink; it is not in AP_CANDIDATES, but a pin
            // must not be able to bypass the default-route check either.
            interfaces = listOf(
                iface("ccmni0", addresses = listOf("10.37.78.54/29")),
                iface("ap0", addresses = listOf("192.168.43.1/24"))
            ),
            defaultRoute = "ccmni0",
            tetherIfaceProp = "ap0",
            frameworkApState = ApEvidence.AP_STATE_ENABLED
        )

        val decision = ApEvidence.evaluate(snap, pin = "ccmni0")

        assertEquals("ap0", decision.iface)
        assertTrue(decision.rejected.any { it.contains("default route") })
    }

    @Test
    fun `a wired LAN with link counts for the phone-to-router topology`() {
        val snap = RadioSnapshot(
            interfaces = listOf(
                iface("usb0", carrier = true),
                iface("ccmni1", addresses = listOf("10.37.78.54/29"))
            ),
            defaultRoute = "ccmni1",
            frameworkApState = 11
        )

        val decision = ApEvidence.evaluate(snap, pin = null)

        assertEquals("usb0", decision.iface)
        assertEquals(ApKind.MANUAL_TOGGLE, decision.kind)
    }

    @Test
    fun `our own root hostapd runtime names the interface`() {
        val snap = RadioSnapshot(
            interfaces = listOf(iface("rnsap0", addresses = listOf("192.168.50.1/24"))),
            defaultRoute = "ccmni0",
            hostapdProcess = true,
            ourHostapd = mapOf(
                "IFACE" to "rnsap0",
                "SSID" to "RNS-Hotspot",
                "PASS" to "hotspot123",
                "CHANNEL" to "6"
            ),
            frameworkApState = 11
        )

        val decision = ApEvidence.evaluate(snap, pin = null)

        assertEquals("rnsap0", decision.iface)
        assertEquals(ApKind.ROOT_HOSTAPD, decision.kind)
        assertEquals("RNS-Hotspot", decision.ssid)
    }

    @Test
    fun `an interface without carrier and without an address proves nothing`() {
        val snap = RadioSnapshot(
            interfaces = listOf(iface("usb0", carrier = false)),
            defaultRoute = "ccmni0",
            frameworkApState = null
        )

        assertNull(ApEvidence.evaluate(snap, pin = null).iface)
    }

    // ------------------------------------------------------------------ snapshot parsing

    @Test
    fun `the one-shot root command is parsed section by section`() {
        val snap = RadioSnapshot.parse(
            listOf(
                "@LINK",
                "1: lo: <LOOPBACK,UP,LOWER_UP> mtu 65536 qdisc noqueue state UNKNOWN",
                "7: p2p0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500 qdisc mq state UP",
                "9: wlan0: <BROADCAST,MULTICAST> mtu 1500 qdisc noop state DOWN",
                "@ADDR",
                "7: p2p0    inet 192.168.49.1/24 brd 192.168.49.255 scope global p2p0\\       valid_lft forever",
                "@ROUTE",
                "default via 10.37.78.41 dev ccmni0 table 1023",
                "@PROP",
                "ap0",
                "p2p0",
                "@PROC",
                "/vendor/bin/hw/hostapd",
                "@CONF",
                "interface=ap0",
                "ssid=RNS-Hotspot",
                "wpa_passphrase=hotspot123",
                "@NETSHARE",
                "@END"
            )
        )

        assertEquals(setOf("lo", "p2p0", "wlan0"), snap.names().toSet())
        assertTrue(snap.iface("p2p0")!!.up)
        assertFalse(snap.iface("wlan0")!!.up)
        assertEquals(listOf("192.168.49.1/24"), snap.iface("p2p0")!!.addresses)
        assertEquals("ccmni0", snap.defaultRoute)
        assertEquals("ap0", snap.tetherIfaceProp)
        assertEquals("p2p0", snap.p2pIfaceProp)
        assertTrue(snap.hostapdProcess)
        assertNotNull(snap.hostapdConf)
        assertEquals("ap0", snap.hostapdConf!!.interfaceName)
        assertEquals("RNS-Hotspot", snap.hostapdConf!!.ssid)
    }

    @Test
    fun `a failing sub-command cannot shift the sections that follow it`() {
        val snap = RadioSnapshot.parse(
            listOf(
                "@LINK",
                "2: ccmni0: <BROADCAST,MULTICAST,UP,LOWER_UP> mtu 1500",
                "@ADDR",
                // nothing: `ip -o -4 addr show` failed
                "@ROUTE",
                "@PROP",
                "@PROC",
                "@CONF",
                "@NETSHARE",
                "@END"
            )
        )
        assertEquals(listOf("ccmni0"), snap.names())
        assertNull(snap.defaultRoute)
        assertNull(snap.tetherIfaceProp)
        assertFalse(snap.hostapdProcess)
        assertNull(snap.hostapdConf)
    }

    @Test
    fun `describeApState speaks the framework constants`() {
        assertEquals("ENABLED", ApEvidence.describeApState(13))
        assertEquals("DISABLED", ApEvidence.describeApState(11))
        assertEquals("unreadable on this build", ApEvidence.describeApState(null))
    }
}
