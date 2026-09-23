package com.hotspot.billing.net

import com.hotspot.billing.util.RootShell

/**
 * Captive portal requests arrive with a source IP (the client's DHCP-leased IP).
 * We need the MAC address to bind the voucher to.
 *
 * Primary source is /proc/net/arp (the phone is the client's gateway, so an entry
 * normally exists). That entry can legitimately be missing or incomplete, so we
 * fall back to the dnsmasq lease file, which always has the MAC<->IP pairing.
 */
object ArpResolver {

    fun macForIp(ip: String): String? = fromArp(ip) ?: fromLeases(ip)

    private fun fromArp(ip: String): String? {
        for (line in RootShell.run("cat /proc/net/arp").out) {
            val parts = line.trim().split(Regex("\\s+"))
            // format: IP address / HW type / Flags / HW address / Mask / Device
            if (parts.size >= 4 && parts[0] == ip) {
                val mac = parts[3]
                if (isRealMac(mac)) return mac.lowercase()
            }
        }
        return null
    }

    private fun fromLeases(ip: String): String? {
        for (line in RootShell.readLeases()) {
            val parts = line.trim().split(Regex("\\s+"))
            // format: <expiry> <mac> <ip> <hostname> <clientid>
            if (parts.size >= 3 && parts[2] == ip && isRealMac(parts[1])) {
                return parts[1].lowercase()
            }
        }
        return null
    }

    private fun isRealMac(value: String): Boolean =
        value.length == 17 && value != "00:00:00:00:00:00" && value.count { it == ':' } == 5
}
