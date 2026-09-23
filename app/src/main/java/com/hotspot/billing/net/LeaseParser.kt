package com.hotspot.billing.net

/**
 * Parses the dnsmasq lease file: one "<expiry> <mac> <ip> <hostname> <clientid>"
 * line per connected client. Pure JVM, so it stays unit-testable.
 */
object LeaseParser {

    data class Lease(val mac: String, val ip: String, val hostname: String)

    fun parse(lines: List<String>): List<Lease> = lines.mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size >= 3 && looksLikeMac(parts[1])) {
            val hostname = if (parts.size >= 4 && parts[3] != "*") parts[3] else ""
            Lease(parts[1].lowercase(), parts[2], hostname)
        } else {
            null
        }
    }

    /**
     * /proc/net/arp rows: "IP HW Flags MAC Mask Device". Incomplete entries
     * (flags 0x0) are neighbours we have not actually talked to.
     * [lanIf] limits the result to the hotspot interface when we know it.
     */
    fun fromArp(lines: List<String>, lanIf: String? = null): List<Lease> = lines.mapNotNull { line ->
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 6) return@mapNotNull null
        val ip = parts[0]
        val flags = parts[2]
        val mac = parts[3]
        val dev = parts[5]
        if (flags == "0x0" || !looksLikeMac(mac)) return@mapNotNull null
        if (lanIf != null && dev != lanIf) return@mapNotNull null
        if (!ip.matches(IP)) return@mapNotNull null
        Lease(mac.lowercase(), ip, "")
    }

    /** Lease-file rows win (they carry the hostname); ARP fills in the rest. */
    fun merge(leases: List<Lease>, arp: List<Lease>): List<Lease> {
        val byMac = LinkedHashMap<String, Lease>()
        for (lease in leases) byMac[lease.mac] = lease
        for (lease in arp) if (lease.mac !in byMac) byMac[lease.mac] = lease
        return byMac.values.toList()
    }

    private val IP = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    private fun looksLikeMac(value: String) =
        value.length == 17 && value != "00:00:00:00:00:00" && value.count { it == ':' } == 5
}
