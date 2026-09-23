package com.hotspot.billing.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class IpPoolTest {

    @Before
    fun drainPool() {
        // IpPool is a process-wide singleton; make each test start from empty.
        (IpPoolTest.START..IpPoolTest.END).forEach { IpPool.release("10.66.0.$it") }
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

    private companion object {
        const val START = 10
        const val END = 49
    }
}
