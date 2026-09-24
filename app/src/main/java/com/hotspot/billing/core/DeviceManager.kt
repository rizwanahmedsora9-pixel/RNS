package com.hotspot.billing.core

import com.hotspot.billing.db.AppDatabase
import com.hotspot.billing.db.DeviceProfile
import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.net.LeaseParser
import com.hotspot.billing.util.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Device Manager — tracks connected clients
 * Merges DHCP leases + ARP + authorized list
 * Updates first_seen, last_seen, download/upload
 */
class DeviceManager(private val db: AppDatabase) {

    data class ConnectedDevice(
        val mac: String,
        val ip: String,
        val hostname: String?,
        val isAuthorized: Boolean,
        val firstSeen: Long,
        val lastSeen: Long,
        val downloadBytes: Long = 0,
        val uploadBytes: Long = 0,
        val label: String? = null
    )

    suspend fun getConnectedDevices(lanIf: String?): List<ConnectedDevice> = withContext(Dispatchers.IO) {
        val leases = try {
            RootShell.connectedClients(lanIf)
        } catch (e: Throwable) {
            emptyList<LeaseParser.Lease>()
        }

        val authorized = try {
            FirewallManager.listAuthorized()
        } catch (e: Throwable) {
            emptyList()
        }

        val profiles = try {
            db.deviceProfileDao().getAll().associateBy { it.mac.lowercase() }
        } catch (e: Throwable) {
            emptyMap()
        }

        leases.map { lease ->
            val macLower = lease.mac.lowercase()
            val profile = profiles[macLower]
            val isAuth = authorized.any { it.contains(lease.mac, ignoreCase = true) } ||
                         lease.mac.lowercase() in authorized.map { it.lowercase() }

            // Update profile first_seen/last_seen automatically
            try {
                val now = System.currentTimeMillis()
                val existing = profile
                if (existing == null) {
                    db.deviceProfileDao().upsert(
                        DeviceProfile(
                            mac = lease.mac,
                            hostname = lease.hostname,
                            firstSeen = now,
                            lastSeen = now
                        )
                    )
                } else {
                    db.deviceProfileDao().upsert(
                        existing.copy(
                            lastSeen = now,
                            hostname = lease.hostname ?: existing.hostname
                        )
                    )
                }
            } catch (e: Throwable) {
                // ignore DB errors
            }

            ConnectedDevice(
                mac = lease.mac,
                ip = lease.ip,
                hostname = lease.hostname,
                isAuthorized = isAuth,
                firstSeen = profile?.firstSeen ?: System.currentTimeMillis(),
                lastSeen = System.currentTimeMillis(),
                label = profile?.label ?: lease.hostname,
                downloadBytes = 0, // TODO: populate via iptables counters or /proc/net/dev
                uploadBytes = 0
            )
        }
    }

    suspend fun getClient(mac: String): ConnectedDevice? {
        val all = getConnectedDevices(null)
        return all.firstOrNull { it.mac.equals(mac, ignoreCase = true) }
    }

    fun getUsageForIp(ip: String): Pair<Long, Long> {
        // Read iptables counters for this IP if available
        return try {
            val result = RootShell.run("iptables -t filter -L HS_FWD -v -n 2>/dev/null | grep $ip", quiet = true)
            // Parse bytes from iptables -v output: "pkts bytes target ..."
            var down = 0L
            var up = 0L
            result.out.forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val bytes = parts[1].toLongOrNull() ?: 0L
                    if (line.contains("dst $ip") || line.contains("dpt") ) {
                        down += bytes
                    } else if (line.contains("src $ip") || line.contains("spt")) {
                        up += bytes
                    }
                }
            }
            up to down
        } catch (e: Throwable) {
            0L to 0L
        }
    }
}
