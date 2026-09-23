package com.hotspot.billing.net

/**
 * The addressing plan the gateway is actually using.
 *
 * Android's hotspot already has an address (usually 192.168.43.1/24) by the
 * time we see the interface. Replacing it with a hardcoded 10.66.0.1 made the
 * phone and this app fight, and clients never finished DHCP. [parse] reads the
 * plan `setup_network.sh` writes after it adopts that address.
 */
data class LanPlan(
    val gateway: String,
    val prefixLength: Int,
    val subnet: String,
    val dhcpStart: String,
    val dhcpEnd: String,
    val portalPort: Int,
    val dhcpOwner: String
) {
    /** "192.168.43." for a /24 gateway of 192.168.43.1. */
    val octetPrefix: String
        get() = gateway.substringBeforeLast('.') + "."

    fun contains(ip: String): Boolean {
        if (prefixLength != 24) return false
        if (!ip.startsWith(octetPrefix)) return false
        val host = ip.removePrefix(octetPrefix)
        if (host.isEmpty() || host.contains('.')) return false
        return host.toIntOrNull() in 0..255
    }

    /** Voucher statics are .10-.49; dnsmasq will not give those to anyone else. */
    fun isStaticPool(ip: String): Boolean {
        if (!contains(ip)) return false
        val host = ip.removePrefix(octetPrefix).toIntOrNull() ?: return false
        return host in STATIC_START..STATIC_END
    }

    companion object {
        const val STATIC_START = 10
        const val STATIC_END = 49

        val DEFAULT = LanPlan(
            gateway = "10.66.0.1",
            prefixLength = 24,
            subnet = "10.66.0.0/24",
            dhcpStart = "10.66.0.10",
            dhcpEnd = "10.66.0.250",
            portalPort = 8080,
            dhcpOwner = "ours"
        )

        fun parse(text: String): LanPlan? {
            val map = LinkedHashMap<String, String>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) continue
                val idx = line.indexOf('=')
                map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
            }
            val gateway = map["LAN_IP"] ?: return null
            if (!gateway.matches(IP)) return null
            return LanPlan(
                gateway = gateway,
                prefixLength = map["LAN_PREFIX"]?.toIntOrNull() ?: 24,
                subnet = map["LAN_SUBNET"].orEmpty(),
                dhcpStart = map["DHCP_START"].orEmpty(),
                dhcpEnd = map["DHCP_END"].orEmpty(),
                portalPort = map["PORTAL_PORT"]?.toIntOrNull() ?: 8080,
                dhcpOwner = map["DHCP_OWNER"] ?: "ours"
            )
        }

        private val IP = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    }
}
