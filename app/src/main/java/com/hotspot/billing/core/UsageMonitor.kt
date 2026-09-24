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
            // One counter dump for the whole tick. The previous version called
            // readLeases() once per matched rule - N processes for one answer.
            val result = RootShell.run("iptables -t filter -L HS_FWD -v -n -x 2>/dev/null; true", quiet = true)
            val leases = RootShell.leases()
            val byMac = LinkedHashMap<String, UsageStats>()

            result.out.forEach { line ->
                if (!line.contains("ACCEPT")) return@forEach
                val ip = Regex("""(\d+\.\d+\.\d+\.\d+)""").find(line)?.value ?: return@forEach
                val bytes = Regex("""^\s*(\d+)\s+(\d+)""").find(line)?.groupValues?.get(2)?.toLongOrNull()
                    ?: return@forEach
                val mac = macFor(ip, leases) ?: return@forEach
                val existing = byMac[mac]
                byMac[mac] = if (existing == null) {
                    UsageStats(mac, ip, downloadBytes = bytes, uploadBytes = 0, totalBytes = bytes)
                } else {
                    existing.copy(
                        downloadBytes = existing.downloadBytes + bytes,
                        totalBytes = existing.totalBytes + bytes
                    )
                }
            }

            // If the counter chain has nothing yet, fall back to interface totals:
            // they are not per client, so they are logged, never written to a
            // client row.
            if (byMac.isEmpty()) {
                RootShell.run("cat /proc/net/dev 2>/dev/null | grep $lanIf; true", quiet = true).out
                    .forEach { line ->
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 10) {
                            val rx = parts[1].toLongOrNull() ?: 0L
                            val tx = parts[9].toLongOrNull() ?: 0L
                            AppLog.i(AppLog.TAG_NET, "usage: $lanIf rx=$rx tx=$tx (total, not per-client)")
                        }
                    }
            }

            byMac.values.toList()
        } catch (e: Throwable) {
            AppLog.w(AppLog.TAG_NET, "usage: collect failed ${e.message}")
            emptyList()
        }
    }

    /** MAC for an IP, from the lease file the caller already read. */
    private fun macFor(ip: String, leases: List<String>): String? {
        for (line in leases) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size >= 3 && parts[2] == ip && parts[1].length == 17) return parts[1].lowercase()
        }
        return null
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
