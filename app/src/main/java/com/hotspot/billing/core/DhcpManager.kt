package com.hotspot.billing.core

import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.util.RootShell

/**
 * Phase 3 — DHCP Server
 * Must guarantee:
 * Client gets:
 *  IP: 192.168.49.x (or current LAN subnet)
 *  Gateway: 192.168.49.1
 *  DNS: 192.168.49.1
 *
 * Features:
 *  - IP pool
 *  - Lease management
 *  - Conflict prevention
 *  - Foreign dnsmasq handling (kill before start, fallback)
 *
 * Database: dhcp_leases table (handled via Room, but also via dnsmasq lease file)
 */
object DhcpManager {

    // Default LAN addressing for new installs — but we ADOPT existing address
    // if Android already put one on ap0 (192.168.43.1 etc) to avoid "Obtaining IP"
    const val DEFAULT_GATEWAY = "192.168.49.1"
    const val DEFAULT_PREFIX = 24
    const val DEFAULT_SUBNET = "192.168.49.0/24"
    const val DEFAULT_START = "192.168.49.10"
    const val DEFAULT_END = "192.168.49.250"
    const val DEFAULT_LEASE = "10m"

    data class DhcpConfig(
        val lanIf: String,
        val gateway: String,
        val prefix: Int,
        val subnet: String,
        val startIp: String,
        val endIp: String,
        val leaseTime: String,
        val dns: String
    )

    data class Lease(
        val mac: String,
        val ip: String,
        val hostname: String?,
        val expiry: Long?
    )

    /**
     * Start DHCP server via setup_network.sh
     * Handles foreign dnsmasq race condition that caused user's log failure:
     *  - Check foreign dnsmasq before start
     *  - Kill foreign dnsmasq
     *  - Start ours
     *  - If fails (Address already in use), try respawn saved foreign cmdline as fallback
     */
    fun start(lanIf: String): Boolean {
        AppLog.i(AppLog.TAG_NET, "dhcp: starting on $lanIf")

        // Check foreign dnsmasq
        val foreignBefore = try {
            RootShell.isForeignDnsmasqRunning()
        } catch (e: Throwable) {
            false
        }
        if (foreignBefore) {
            AppLog.w(AppLog.TAG_NET, "dhcp: foreign dnsmasq detected before start, will be killed by setup_network.sh")
        }

        val result = RootShell.startNetwork()
        if (!result.isSuccess) {
            AppLog.e(AppLog.TAG_NET, "dhcp: setup_network.sh start failed exit=${result.code}")
            // Try keepalive as repair
            val keepalive = RootShell.keepaliveNetwork()
            if (!keepalive.isSuccess) {
                AppLog.e(AppLog.TAG_NET, "dhcp: keepalive also failed")
                return false
            }
        }

        // Verify our dnsmasq is running
        val ourRunning = try {
            RootShell.isDnsmasqRunning()
        } catch (e: Throwable) {
            false
        }

        val foreignAfter = try {
            RootShell.isForeignDnsmasqRunning()
        } catch (e: Throwable) {
            false
        }

        AppLog.i(AppLog.TAG_NET, "dhcp: after start - ourRunning=$ourRunning foreignRunning=$foreignAfter")

        if (!ourRunning && foreignAfter) {
            AppLog.w(AppLog.TAG_NET, "dhcp: our dnsmasq not running but foreign is - Android took over, adopting")
            // This is actually OK per README: if Android's DHCP server comes back and would fight,
            // the app steps aside and lets phone hand out addresses — portal still works
            return true
        }

        if (!ourRunning && !foreignAfter) {
            AppLog.e(AppLog.TAG_NET, "dhcp: NO DHCP server running after start!")
            return false
        }

        AppLog.i(AppLog.TAG_NET, "dhcp: started successfully on $lanIf")
        return true
    }

    fun stop(): Boolean {
        AppLog.i(AppLog.TAG_NET, "dhcp: stopping")
        val result = RootShell.stopNetwork()
        return result.isSuccess
    }

    fun isAlive(): Boolean {
        val our = try { RootShell.isDnsmasqRunning() } catch (e: Throwable) { false }
        val foreign = try { RootShell.isForeignDnsmasqRunning() } catch (e: Throwable) { false }
        return our || foreign
    }

    fun restart(lanIf: String): Boolean {
        AppLog.i(AppLog.TAG_NET, "dhcp: restarting on $lanIf (granular heal)")
        // Granular restart: only DHCP, not whole gateway
        // setup_network.sh keepalive re-asserts DHCP without flushing address
        val keepalive = RootShell.keepaliveNetwork()
        if (keepalive.isSuccess && isAlive()) {
            AppLog.i(AppLog.TAG_NET, "dhcp: keepalive repaired DHCP")
            return true
        }
        // Full restart if keepalive failed
        AppLog.w(AppLog.TAG_NET, "dhcp: keepalive failed, doing full stop/start")
        stop()
        Thread.sleep(1000)
        return start(lanIf)
    }

    fun reserve(mac: String, ip: String): Boolean {
        AppLog.i(AppLog.TAG_NET, "dhcp: reserve $mac -> $ip")
        val result = RootShell.reserveIp(mac, ip)
        return result.isSuccess
    }

    fun unreserve(mac: String): Boolean {
        AppLog.i(AppLog.TAG_NET, "dhcp: unreserve $mac")
        val result = RootShell.releaseIp(mac)
        return result.isSuccess
    }

    fun getLeases(): List<Lease> {
        return try {
            val raw = RootShell.readLeases()
            raw.mapNotNull { line ->
                // Format: <expiry> <mac> <ip> <hostname> <clientid>
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 3) {
                    val expiry = parts[0].toLongOrNull()
                    val mac = parts[1]
                    val ip = parts[2]
                    val hostname = if (parts.size >= 4) parts[3] else null
                    if (mac.matches(Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")) &&
                        ip.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$"))) {
                        Lease(mac, ip, hostname, expiry)
                    } else null
                } else null
            }
        } catch (e: Throwable) {
            AppLog.w(AppLog.TAG_NET, "dhcp: getLeases failed ${e.message}")
            emptyList()
        }
    }

    fun getConfig(lanIf: String): DhcpConfig? {
        val plan = RootShell.readLanPlan()
        return if (plan != null) {
            DhcpConfig(
                lanIf = lanIf,
                gateway = plan.gateway,
                prefix = plan.prefixLength,
                subnet = plan.subnet,
                startIp = plan.dhcpStart,
                endIp = plan.dhcpEnd,
                leaseTime = DEFAULT_LEASE,
                dns = plan.gateway // DNS = gateway itself
            )
        } else {
            // Fallback to defaults
            DhcpConfig(
                lanIf = lanIf,
                gateway = DEFAULT_GATEWAY,
                prefix = DEFAULT_PREFIX,
                subnet = DEFAULT_SUBNET,
                startIp = DEFAULT_START,
                endIp = DEFAULT_END,
                leaseTime = DEFAULT_LEASE,
                dns = DEFAULT_GATEWAY
            )
        }
    }
}
