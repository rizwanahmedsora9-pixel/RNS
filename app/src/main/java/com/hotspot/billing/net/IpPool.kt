package com.hotspot.billing.net

/**
 * Simple static-IP allocator out of the LAN subnet range that's excluded
 * from dnsmasq's dynamic DHCP range (10.66.0.50-250 is dynamic; this pool
 * uses 10.66.0.10-49 for voucher-assigned static IPs).
 */
object IpPool {
    private const val BASE = "10.66.0."
    private const val START = 10
    private const val END = 49
    private val inUse = mutableSetOf<String>()

    @Synchronized
    fun allocate(): String? {
        for (i in START..END) {
            val ip = "$BASE$i"
            if (ip !in inUse) {
                inUse.add(ip)
                return ip
            }
        }
        return null // pool exhausted
    }

    @Synchronized
    fun release(ip: String) {
        inUse.remove(ip)
    }

    @Synchronized
    fun markUsed(ip: String) {
        inUse.add(ip)
    }
}
