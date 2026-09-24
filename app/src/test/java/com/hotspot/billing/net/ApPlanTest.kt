package com.hotspot.billing.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApPlanTest {

    @Test
    fun `Hot 8 automatic mode tries the system hotspot before WiFi Direct`() {
        // API 28, uplink is mobile data. The log proved ap0 can beacon and that
        // WiFi Direct returns BUSY while the radio is off.
        val steps = ApPlan.steps(ApMode.AUTO, sdk = 28, wanIsWifi = false)
        assertEquals(ApPlan.Step.SYSTEM, steps.first())
        assertTrue(steps.contains(ApPlan.Step.WIFI_DIRECT))
        assertTrue(steps.indexOf(ApPlan.Step.SYSTEM) < steps.indexOf(ApPlan.Step.WIFI_DIRECT))
    }

    @Test
    fun `a WiFi uplink does not lead with the system hotspot`() {
        val steps = ApPlan.steps(ApMode.AUTO, sdk = 28, wanIsWifi = true)
        assertEquals(ApPlan.Step.LOCAL_ONLY, steps.first())
        assertEquals(ApPlan.Step.SYSTEM, steps.last())
    }

    @Test
    fun `NetShare still tries WiFi Direct first, then the system hotspot on mobile`() {
        val steps = ApPlan.steps(ApMode.NETSHARE, sdk = 28, wanIsWifi = false)
        assertEquals(
            listOf(
                ApPlan.Step.WIFI_DIRECT,
                ApPlan.Step.SYSTEM,
                ApPlan.Step.LOCAL_ONLY,
                ApPlan.Step.ROOT_HOSTAPD
            ),
            steps
        )
    }

    @Test
    fun `manual mode creates nothing`() {
        assertTrue(ApPlan.steps(ApMode.MANUAL, sdk = 28, wanIsWifi = false).isEmpty())
    }

    @Test
    fun `ccmni0 is mobile data, not a WiFi radio`() {
        assertTrue(ApPlan.isMobileIface("ccmni0"))
        assertTrue(ApPlan.isMobileIface("rmnet_data0"))
        assertFalse(ApPlan.isWifiRadio("ccmni0"))
        assertTrue(ApPlan.isWifiRadio("wlan0"))
        assertFalse(ApPlan.isMobileIface("wlan0"))
    }

    @Test
    fun `the vendor HAL hostapd is not a CLI`() {
        assertTrue(ApPlan.isHalHostapd("/vendor/bin/hw/hostapd"))
        assertTrue(ApPlan.isHalHostapd("HAL:/vendor/bin/hw/hostapd"))
        assertFalse(ApPlan.isHalHostapd("/system/bin/hostapd"))
    }

    @Test
    fun `service call parcel dump yields the AP state int`() {
        val dump = """
            Result: Parcel(
              0x00000000: 0000000b 00000000 00000000 00000000 '............'
            )
        """.trimIndent()
        assertEquals(11, ApPlan.parseServiceCallInt(dump))
        assertTrue(ApPlan.looksLikeApState(11))
        assertFalse(ApPlan.looksLikeApState(0))
        assertNull(ApPlan.parseServiceCallInt("not a parcel"))
    }

    @Test
    fun `system softap interfaces are the ones whose dnsmasq must be left alone`() {
        assertTrue(ApPlan.frameworkLikelyOwnsDhcp("ap0"))
        assertFalse(ApPlan.frameworkLikelyOwnsDhcp("p2p0"))
        assertFalse(ApPlan.frameworkLikelyOwnsDhcp("rnsap0"))
    }

    @Test
    fun `BUSY while WiFi is off is not another group`() {
        val hint = ApPlan.p2pBusyHint(
            wifiEnabled = false,
            locationPermission = true,
            locationServicesOn = true
        )
        assertTrue(hint.contains("not that another group"))
        assertTrue(hint.contains("WiFi is off"))
    }

    // ------------------------------------------------------- learning this radio

    @Test
    fun `the method that worked before leads the next attempt`() {
        // What a chipset can start is a hardware question: on the Hot 8 the WiFi
        // Direct group owner beacons, and re-learning that costs a system-hotspot
        // attempt (10-20 s of framework calls) on every start.
        val planned = ApPlan.steps(ApMode.AUTO, sdk = 28, wanIsWifi = false)
        val ordered = ApPlan.withRememberedFirst(planned, ApPlan.Step.WIFI_DIRECT)

        assertEquals(ApPlan.Step.WIFI_DIRECT, ordered.first())
        assertEquals(planned.filter { it != ApPlan.Step.WIFI_DIRECT }, ordered.drop(1))
        assertEquals(4, ordered.size)
    }

    @Test
    fun `a remembered method that this mode never runs is ignored`() {
        val planned = listOf(ApPlan.Step.SYSTEM)
        assertEquals(planned, ApPlan.withRememberedFirst(planned, ApPlan.Step.WIFI_DIRECT))
    }

    @Test
    fun `nothing remembered keeps the default order`() {
        val planned = ApPlan.steps(ApMode.AUTO, sdk = 28, wanIsWifi = false)
        assertEquals(planned, ApPlan.withRememberedFirst(planned, null))
    }

    @Test
    fun `every AP kind that can repeat maps back to a step`() {
        assertEquals(ApPlan.Step.SYSTEM, ApPlan.stepFor(ApKind.SYSTEM_HOTSPOT))
        assertEquals(ApPlan.Step.LOCAL_ONLY, ApPlan.stepFor(ApKind.LOCAL_ONLY))
        assertEquals(ApPlan.Step.WIFI_DIRECT, ApPlan.stepFor(ApKind.WIFI_DIRECT))
        assertEquals(ApPlan.Step.ROOT_HOSTAPD, ApPlan.stepFor(ApKind.ROOT_HOSTAPD))
        // A hotspot the operator flipped on (or a wired LAN) is not a method we
        // can repeat, so it is never remembered.
        assertNull(ApPlan.stepFor(ApKind.MANUAL_TOGGLE))
        assertNull(ApPlan.stepFor(null))
    }

    @Test
    fun `p2p0 is on the candidate list but is not evidence on its own`() {
        assertTrue("p2p0" in ApPlan.AP_CANDIDATES)
        // The trap the 14:42 log fell into: p2p0 is UP whenever WiFi is.
        val snap = RadioSnapshot(
            interfaces = listOf(
                RadioSnapshot.Iface("p2p0", up = true, carrier = true, addresses = emptyList())
            ),
            defaultRoute = "ccmni0",
            p2pIfaceProp = "p2p0",
            frameworkApState = 11,
            p2pGroupOwner = false
        )
        assertNull(ApEvidence.evaluate(snap, pin = null).iface)
    }
}
