package com.hotspot.billing.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IpPoolTest {

    @Before
    fun drainPool() {
        // IpPool is a process-wide singleton; make each test start from the
        // default 10.66.0.0/24 plan and an empty pool.
        IpPool.configure(LanPlan.DEFAULT)
        (IpPoolTest.START..IpPoolTest.END).forEach {
            IpPool.release("10.66.0.$it")
            IpPool.release("192.168.43.$it")
        }
    }

    @Test
    fun `allocates from the bottom of the static range`() {
        assertEquals("10.66.0.10", IpPool.allocate())
        assertEquals("10.66.0.11", IpPool.allocate())
    }

    @Test
    fun `never hands out the same address twice`() {
        val seen = mutableSetOf<String>()
        repeat(40) {
            val ip = IpPool.allocate()
            assertTrue("pool returned null early", ip != null)
            assertTrue("duplicate allocation: $ip", seen.add(ip!!))
        }
        assertEquals(40, seen.size)
    }

    @Test
    fun `reports exhaustion instead of overflowing into the dynamic range`() {
        repeat(40) { IpPool.allocate() }
        assertNull(IpPool.allocate())
    }

    @Test
    fun `released addresses are reusable`() {
        val first = IpPool.allocate()
        IpPool.release(first!!)
        assertEquals(first, IpPool.allocate())
    }

    @Test
    fun `markUsed reserves an address the database already knows about`() {
        IpPool.markUsed("10.66.0.10")
        assertEquals("10.66.0.11", IpPool.allocate())
    }

    @Test
    fun `configure follows the subnet Android already assigned`() {
        IpPool.configure(
            LanPlan(
                gateway = "192.168.43.1",
                prefixLength = 24,
                subnet = "192.168.43.0/24",
                dhcpStart = "192.168.43.10",
                dhcpEnd = "192.168.43.250",
                portalPort = 8080,
                dhcpOwner = "android"
            )
        )
        assertEquals("192.168.43.10", IpPool.allocate())
        assertTrue(IpPool.inStaticPool("192.168.43.10"))
        assertEquals(false, IpPool.inStaticPool("10.66.0.10"))
    }

    private companion object {
        const val START = 10
        const val END = 49
    }
}
