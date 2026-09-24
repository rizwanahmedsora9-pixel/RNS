package com.hotspot.billing.core

import com.hotspot.billing.db.AppDatabase
import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.util.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Usage Monitor — tracks data usage per client
 * Reads iptables counters or /proc/net/dev
 * Updates clients table with download/upload bytes
 * Checks data_limit and auto-expires voucher if exceeded
 */
class UsageMonitor(
    private val db: AppDatabase
) {

    data class UsageStats(
        val mac: String,
        val ip: String,
        val downloadBytes: Long,
        val uploadBytes: Long,
        val totalBytes: Long
    )

    suspend fun collectUsage(lanIf: String?): List<UsageStats> = withContext(Dispatchers.IO) {
        if (lanIf == null) return@withContext emptyList()

        try {
            // Try reading iptables counters with -v
            val result = RootShell.run("iptables -t filter -L HS_FWD -v -n -x 2>/dev/null", quiet = true)
            val stats = mutableMapOf<String, UsageStats>()

            // Also try reading from /proc/net/dev for interface totals
            val devResult = RootShell.run("cat /proc/net/dev 2>/dev/null | grep $lanIf", quiet = true)

            // Parse iptables output: pkts bytes target prot ...
            // Example: "  100  50000 ACCEPT all -- * * 192.168.49.10 0.0.0.0"
            result.out.forEach { line ->
                if (!line.contains("ACCEPT") && !line.contains("HS_")) return@forEach
                // Extract IP and bytes
                val ipMatch = Regex("""(\d+\.\d+\.\d+\.\d+)""").findAll(line).toList()
                val bytesMatch = Regex("""^\s*\d+\s+(\d+)""").find(line)

                if (ipMatch.isNotEmpty() && bytesMatch != null) {
                    val bytes = bytesMatch.groupValues[1].toLongOrNull() ?: 0L
                    val ip = ipMatch.firstOrNull()?.value ?: return@forEach

                    // Try to find MAC for this IP from leases
                    val mac = findMacForIp(ip) ?: return@forEach

                    val existing = stats[mac]
                    if (existing == null) {
                        stats[mac] = UsageStats(mac, ip, downloadBytes = bytes, uploadBytes = 0, totalBytes = bytes)
                    } else {
                        // Heuristic: if line contains dst IP, it's download
                        val isDownload = line.contains("dst") || line.contains(ip) && line.contains("0.0.0.0/0")
                        if (isDownload) {
                            stats[mac] = existing.copy(downloadBytes = existing.downloadBytes + bytes, totalBytes = existing.totalBytes + bytes)
                        } else {
                            stats[mac] = existing.copy(uploadBytes = existing.uploadBytes + bytes, totalBytes = existing.totalBytes + bytes)
                        }
                    }
                }
            }

            // If no iptables stats, try /proc/net/dev for total
            if (stats.isEmpty()) {
                devResult.out.forEach { line ->
                    // Inter-|   Receive ... | Transmit ...
                    //  wlan0: 12345 0 0 0 0 0 0 0 54321 0 0 0 0 0 0 0
                    val parts = line.trim().split(Regex("\\s+"))
                    if (parts.size >= 10) {
                        val rx = parts[1].toLongOrNull() ?: 0L
                        val tx = parts[9].toLongOrNull() ?: 0L
                        AppLog.i(AppLog.TAG_NET, "usage: $lanIf rx=$rx tx=$tx (total, not per-client)")
                    }
                }
            }

            stats.values.toList()
        } catch (e: Throwable) {
            AppLog.w(AppLog.TAG_NET, "usage: collect failed ${e.message}")
            emptyList()
        }
    }

    private fun findMacForIp(ip: String): String? {
        return try {
            val leases = RootShell.readLeases()
            leases.firstOrNull { it.contains(ip) }?.let { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 3) parts[1] else null
            }
        } catch (e: Throwable) {
            null
        }
    }

    suspend fun updateDatabase(stats: List<UsageStats>) = withContext(Dispatchers.IO) {
        stats.forEach { stat ->
            try {
                val existing = db.clientDao().findByMac(stat.mac)
                if (existing != null) {
                    db.clientDao().updateUsage(stat.mac, stat.downloadBytes, stat.uploadBytes, System.currentTimeMillis())
                } else {
                    // Create client entry
                    db.clientDao().upsert(
                        com.hotspot.billing.db.Client(
                            mac = stat.mac,
                            ip = stat.ip,
                            firstSeen = System.currentTimeMillis(),
                            lastSeen = System.currentTimeMillis(),
                            downloadBytes = stat.downloadBytes,
                            uploadBytes = stat.uploadBytes
                        )
                    )
                }

                // Also update session bytes if there's an open session
                val openSessions = db.sessionDao().getOpenByMac(stat.mac)
                openSessions.forEach { session ->
                    // Update session with latest bytes (we don't have exact per-session, use total for now)
                    // Note: session close will set final bytes
                }

                AppLog.i(AppLog.TAG_NET, "usage: ${stat.mac} ${stat.ip} down=${stat.downloadBytes} up=${stat.uploadBytes}")
            } catch (e: Throwable) {
                AppLog.w(AppLog.TAG_NET, "usage: update DB failed for ${stat.mac}: ${e.message}")
            }
        }
    }

    suspend fun checkAndEnforceLimits(billingManager: BillingManager) = withContext(Dispatchers.IO) {
        val clients = db.clientDao().getAll()
        clients.forEach { client ->
            if (client.isBlocked) return@forEach

            try {
                val exceeded = billingManager.checkDataLimitExceeded(client.mac)
                if (exceeded) {
                    AppLog.w(AppLog.TAG_NET, "usage: data limit exceeded for ${client.mac}, blocking")
                    // Deauthorize and block
                    FirewallManager.deauthorize(client.mac)
                    db.clientDao().setBlocked(client.mac, true)

                    // Expire voucher
                    val voucher = db.voucherDao().findActiveByMac(client.mac)
                    if (voucher != null) {
                        db.voucherDao().upsert(voucher.copy(status = com.hotspot.billing.db.VoucherStatus.EXPIRED))
                    }
                }
            } catch (e: Throwable) {
                AppLog.w(AppLog.TAG_NET, "usage: limit check failed for ${client.mac}: ${e.message}")
            }
        }
    }
}
