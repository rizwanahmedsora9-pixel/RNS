package com.hotspot.billing.net

/**
 * Static-IP allocator for voucher bindings.
 *
 * The pool is .10-.49 of whatever /24 the hotspot is actually using. That used
 * to be hardcoded to 10.66.0.x, which is only right when this app created the
 * subnet. On a normal Android hotspot the subnet is 192.168.43.0/24 (or a
 * random 192.168.x.0/24); handing out 10.66.0.x then matched no client.
 */
object IpPool {
    private var prefix = LanPlan.DEFAULT.octetPrefix
    private var activePlan: LanPlan = LanPlan.DEFAULT
    private val inUse = mutableSetOf<String>()

    @Synchronized
    fun configure(plan: LanPlan) {
        val newPrefix = plan.octetPrefix
        if (newPrefix != prefix) {
            inUse.retainAll { it.startsWith(newPrefix) }
            prefix = newPrefix
        }
        activePlan = plan
    }

    @Synchronized
    fun currentPlan(): LanPlan = activePlan

    /** True when [ip] is one of this subnet's voucher statics (.10-.49). */
    @Synchronized
    fun inStaticPool(ip: String): Boolean = activePlan.isStaticPool(ip)

    @Synchronized
    fun allocate(): String? {
        for (i in LanPlan.STATIC_START..LanPlan.STATIC_END) {
            val ip = "$prefix$i"
            if (ip !in inUse) {
                inUse.add(ip)
                return ip
            }
        }
        return null
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
