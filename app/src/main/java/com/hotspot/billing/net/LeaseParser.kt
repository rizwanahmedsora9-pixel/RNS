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

    private fun looksLikeMac(value: String) =
        value.length == 17 && value != "00:00:00:00:00:00" && value.count { it == ':' } == 5
}
