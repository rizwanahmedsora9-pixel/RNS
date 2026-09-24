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
}
