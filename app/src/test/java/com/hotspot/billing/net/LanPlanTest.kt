package com.hotspot.billing.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanPlanTest {

    @Test
    fun `parses the runtime file the shell script writes`() {
        val plan = LanPlan.parse(
            """
            LAN_IP=192.168.43.1
            LAN_PREFIX=24
            LAN_SUBNET=192.168.43.0/24
            DHCP_START=192.168.43.10
            DHCP_END=192.168.43.250
            PORTAL_PORT=8080
            DHCP_OWNER=android
            """.trimIndent()
        )
        assertEquals("192.168.43.1", plan!!.gateway)
        assertEquals("android", plan.dhcpOwner)
        assertEquals("192.168.43.", plan.octetPrefix)
        assertTrue(plan.isStaticPool("192.168.43.10"))
        assertFalse(plan.isStaticPool("192.168.43.137"))
        assertFalse(plan.isStaticPool("10.66.0.10"))
    }

    @Test
    fun `rejects a file with no gateway`() {
        assertNull(LanPlan.parse("DHCP_OWNER=ours\n"))
    }
}

/**
 * setup_network.sh now also records which interface it configured, as
 * LAN_IF_USED. It must not be read back as LAN_IF (keepalive sources this file,
 * and a stale LAN_IF would override the interface hotspot.env was just updated
 * with), and it must not break parsing.
 */
class LanPlanRuntimeKeysTest {

    @Test
    fun `LAN_IF_USED is ignored by the plan parser`() {
        val plan = LanPlan.parse(
            """
            LAN_IF_USED=p2p-wlan0-0
            LAN_IP=192.168.49.1
            LAN_PREFIX=24
            LAN_SUBNET=192.168.49.0/24
            DHCP_START=192.168.49.10
            DHCP_END=192.168.49.250
            PORTAL_PORT=8080
            DHCP_OWNER=ours-dns
            """.trimIndent()
        )
        assertEquals("192.168.49.1", plan!!.gateway)
        assertEquals("ours-dns", plan.dhcpOwner)
        assertEquals("192.168.49.", plan.octetPrefix)
        assertTrue(plan.isStaticPool("192.168.49.20"))
    }

    @Test
    fun `a NetShare subnet is treated exactly like the hotspot one`() {
        val plan = LanPlan.parse("LAN_IP=192.168.50.1\nLAN_SUBNET=192.168.50.0/24\n")!!
        assertTrue(plan.contains("192.168.50.100"))
        assertTrue(!plan.contains("192.168.49.100"))
        assertTrue(plan.isStaticPool("192.168.50.49"))
        assertTrue(!plan.isStaticPool("192.168.50.50"))
    }
}
