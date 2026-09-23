package com.hotspot.billing.db

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class VoucherStatus { UNUSED, ACTIVE, EXPIRED }

@Entity(tableName = "vouchers")
data class Voucher(
    @PrimaryKey val code: String,          // e.g. "AB12-CD34"
    val planName: String,                  // e.g. "1 Hour - 2Mbps"
    val durationMinutes: Int,
    val rateKbit: Int,
    val ceilKbit: Int,
    val status: VoucherStatus = VoucherStatus.UNUSED,
    val boundMac: String? = null,          // set on first redemption; locks the voucher to this device
    val assignedIp: String? = null,        // static IP given to boundMac
    val activatedAt: Long? = null,         // epoch millis
    val expiresAt: Long? = null,
    val classId: Int? = null,              // tc class id assigned to this session
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "sessions")
data class UserSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mac: String,
    val ip: String,
    val voucherCode: String,
    val connectedAt: Long,
    val disconnectedAt: Long? = null,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val deviceLabel: String? = null   // optional, e.g. user-agent-derived hint for profiling
)

/**
 * A known client device - the "user profile" side of the billing system.
 * Rows are created automatically the first time a device is seen on the LAN
 * (DHCP lease or voucher redemption); the operator fills in who it belongs to.
 */
@Entity(tableName = "device_profiles")
data class DeviceProfile(
    @PrimaryKey val mac: String,
    val label: String? = null,        // e.g. "Ali - Samsung A12"
    val phone: String? = null,
    val note: String? = null,
    val hostname: String? = null,     // from the DHCP lease, e.g. "android-4f2..."
    val firstSeen: Long = System.currentTimeMillis(),
    val lastSeen: Long = System.currentTimeMillis()
)
