package com.hotspot.billing.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Phase 7 — Database Design
 * Additional tables required by master plan
 */

@Entity(tableName = "dhcp_leases")
data class DhcpLease(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mac: String,
    val ip: String,
    val hostname: String?,
    val startTime: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    val expiry: Long? = null
)

@Entity(tableName = "hotspot_sessions")
data class HotspotSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val start: Long = System.currentTimeMillis(),
    val stop: Long? = null,
    val mode: String, // ApMode.key
    val status: String, // RUNNING, STOPPED, FAILED
    val dataUsed: Long = 0,
    val wanIf: String? = null,
    val lanIf: String? = null,
    val ssid: String? = null
)

@Entity(tableName = "clients")
data class Client(
    @PrimaryKey val mac: String,
    val ip: String,
    val name: String? = null,
    val hostname: String? = null,
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis(),
    val downloadBytes: Long = 0,
    val uploadBytes: Long = 0,
    val isBlocked: Boolean = false,
    val speedLimitKbit: Int? = null
)

@Entity(tableName = "payments")
data class Payment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val voucherId: String, // voucher code
    val amount: Double,
    val date: Long = System.currentTimeMillis(),
    val method: String = "CASH" // CASH, ONLINE, etc
)

@Entity(tableName = "settings")
data class Setting(
    @PrimaryKey val key: String,
    val value: String
)

// Enhanced voucher with data_limit and price per master plan
// We keep existing Voucher table but add new fields via migration or new table
// For now, create VoucherPlan for pricing
@Entity(tableName = "voucher_plans")
data class VoucherPlan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, // "1 Hour", "1 Day"
    val durationMinutes: Int,
    val dataLimitMb: Int, // 500, 1024, 5120
    val price: Double,
    val rateKbit: Int,
    val ceilKbit: Int
)
