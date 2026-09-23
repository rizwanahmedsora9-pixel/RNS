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
