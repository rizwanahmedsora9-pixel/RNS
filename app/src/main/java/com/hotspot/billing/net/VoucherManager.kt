package com.hotspot.billing.net

import com.hotspot.billing.db.AppDatabase
import com.hotspot.billing.db.UserSession
import com.hotspot.billing.db.Voucher
import com.hotspot.billing.db.VoucherStatus
import com.hotspot.billing.util.RootShell
import java.util.concurrent.atomic.AtomicInteger

sealed class RedeemResult {
    data class Success(val ip: String, val expiresAt: Long) : RedeemResult()
    object InvalidCode : RedeemResult()
    object AlreadyUsedOnAnotherDevice : RedeemResult()
    object Expired : RedeemResult()
    object PoolExhausted : RedeemResult()
}

class VoucherManager(private val db: AppDatabase) {

    private val classIdCounter = AtomicInteger(0)

    @Volatile
    private var restored = false

    /**
     * [IpPool] and the tc class counter live in memory, so after a process restart
     * they know nothing about vouchers that are still active. Re-seed both from the
     * database before the first redemption, otherwise the pool hands out IPs that
     * are already bound and tc class ids collide with live ones.
     */
    @Synchronized
    private fun ensureRestored() {
        if (restored) return
        var highest = CLASS_ID_START
        for (voucher in db.voucherDao().getActive()) {
            voucher.assignedIp?.let { IpPool.markUsed(it) }
            voucher.classId?.let { if (it > highest) highest = it }
        }
        classIdCounter.set(highest)
        restored = true
    }

    /**
     * Called by the captive portal when a user submits a voucher code.
     * requestingMac = MAC address of the device making the request, resolved from
     * the client's source IP on the LAN (ARP table, falling back to dnsmasq leases).
     */
    @Synchronized
    fun redeem(code: String, requestingMac: String): RedeemResult {
        ensureRestored()

        val voucher = db.voucherDao().findByCode(VoucherCodes.normalize(code))
            ?: return RedeemResult.InvalidCode

        val now = System.currentTimeMillis()

        return when (voucher.status) {
            VoucherStatus.UNUSED -> activateFresh(voucher, requestingMac, now)

            VoucherStatus.ACTIVE -> {
                val boundMac = voucher.boundMac
                val ip = voucher.assignedIp
                val expiresAt = voucher.expiresAt

                when {
                    // ACTIVE without a binding is a corrupt row - put it back on sale.
                    boundMac == null || ip == null || expiresAt == null -> {
                        ip?.let { IpPool.release(it) }
                        db.voucherDao().upsert(
                            voucher.copy(
                                status = VoucherStatus.UNUSED, boundMac = null, assignedIp = null,
                                activatedAt = null, expiresAt = null, classId = null
                            )
                        )
                        RedeemResult.InvalidCode
                    }

                    now > expiresAt -> {
                        expireVoucher(voucher)
                        RedeemResult.Expired
                    }

                    // Core rule: the voucher is locked to the first device that used it.
                    boundMac != requestingMac -> RedeemResult.AlreadyUsedOnAnotherDevice

                    // Same device re-submitting (reconnect, or the phone/hotspot was
                    // restarted and the kernel rules are gone): re-apply them instead
                    // of just reporting success.
                    else -> {
                        RootShell.authorizeMac(boundMac, ip)
                        voucher.classId?.let {
                            RootShell.addBandwidthClass(ip, it, voucher.rateKbit, voucher.ceilKbit)
                        }
                        IpPool.markUsed(ip)
                        db.sessionDao().insert(
                            UserSession(mac = boundMac, ip = ip, voucherCode = voucher.code, connectedAt = now)
                        )
                        RedeemResult.Success(ip, expiresAt)
                    }
                }
            }

            VoucherStatus.EXPIRED -> RedeemResult.Expired
        }
    }

    private fun activateFresh(voucher: Voucher, mac: String, now: Long): RedeemResult {
        val ip = IpPool.allocate() ?: return RedeemResult.PoolExhausted
        val expiresAt = now + voucher.durationMinutes * 60_000L
        val classId = classIdCounter.incrementAndGet()

        val updated = voucher.copy(
            status = VoucherStatus.ACTIVE,
            boundMac = mac,
            assignedIp = ip,
            activatedAt = now,
            expiresAt = expiresAt,
            classId = classId
        )
        db.voucherDao().upsert(updated)

        // Bind MAC<->IP at the firewall level (only this MAC+IP pair can pass NAT),
        // reserve the same IP in DHCP so the client actually gets it, then cap it.
        RootShell.reserveIp(mac, ip)
        RootShell.authorizeMac(mac, ip)
        RootShell.addBandwidthClass(ip, classId, voucher.rateKbit, voucher.ceilKbit)

        db.sessionDao().insert(
            UserSession(mac = mac, ip = ip, voucherCode = voucher.code, connectedAt = now)
        )

        return RedeemResult.Success(ip, expiresAt)
    }

    private fun expireVoucher(voucher: Voucher) {
        db.voucherDao().upsert(voucher.copy(status = VoucherStatus.EXPIRED))
        voucher.boundMac?.let {
            RootShell.deauthorizeMac(it)
            RootShell.releaseIp(it)
        }
        voucher.assignedIp?.let { ip ->
            IpPool.release(ip)
            voucher.classId?.let { RootShell.removeBandwidthClass(ip, it) }
        }
        voucher.boundMac?.let { mac ->
            val now = System.currentTimeMillis()
            db.sessionDao().getOpenByMac(mac).forEach { session ->
                db.sessionDao().close(session.id, now, session.bytesUp, session.bytesDown)
            }
        }
    }

    /** Call periodically (e.g. WorkManager every minute) to sweep expired vouchers. */
    fun sweepExpired() {
        ensureRestored()
        val now = System.currentTimeMillis()
        db.voucherDao().getExpired(now).forEach { expireVoucher(it) }
    }

    /**
     * Admin action: end a voucher right now, whatever state it is in. Kicks the
     * client off the network (rules + reservation + shaping) and closes its sessions.
     */
    @Synchronized
    fun forceExpire(code: String): Boolean {
        ensureRestored()
        val voucher = db.voucherDao().findByCode(VoucherCodes.normalize(code)) ?: return false
        return when (voucher.status) {
            VoucherStatus.EXPIRED -> true
            VoucherStatus.UNUSED -> {
                db.voucherDao().upsert(voucher.copy(status = VoucherStatus.EXPIRED))
                true
            }
            VoucherStatus.ACTIVE -> {
                expireVoucher(voucher)
                true
            }
        }
    }

    /** Admin action: remove a voucher from the database entirely. */
    @Synchronized
    fun deleteVoucher(code: String): Boolean {
        ensureRestored()
        val voucher = db.voucherDao().findByCode(VoucherCodes.normalize(code)) ?: return false
        if (voucher.status == VoucherStatus.ACTIVE) {
            expireVoucher(voucher)
        }
        db.voucherDao().delete(voucher.code)
        return true
    }

    /**
     * After a service restart or an interface bounce the kernel rules for
     * ACTIVE vouchers are gone even though the DB rows are fine. Re-apply DHCP
     * reservations, firewall authorization and shaping for every live voucher.
     */
    @Synchronized
    fun reapplyAll() {
        ensureRestored()
        db.voucherDao().getActive().forEach { voucher ->
            val mac = voucher.boundMac
            val ip = voucher.assignedIp
            if (mac != null && ip != null) {
                RootShell.reserveIp(mac, ip)
                RootShell.authorizeMac(mac, ip)
                voucher.classId?.let {
                    RootShell.addBandwidthClass(ip, it, voucher.rateKbit, voucher.ceilKbit)
                }
                IpPool.markUsed(ip)
            }
        }
    }

    /** Generates a batch of fresh voucher codes for a given plan. */
    fun generateBatch(
        count: Int,
        planName: String,
        durationMinutes: Int,
        rateKbit: Int,
        ceilKbit: Int
    ): List<String> {
        val dao = db.voucherDao()
        val codes = mutableListOf<String>()
        repeat(count) {
            // Retry until we hit a code that is not already in the table, so a
            // collision can never REPLACE a voucher that is already on sale.
            var code = VoucherCodes.generate()
            var guard = 0
            while (dao.findByCode(code) != null && guard++ < CODE_RETRY_LIMIT) {
                code = VoucherCodes.generate()
            }
            dao.upsert(
                Voucher(
                    code = code,
                    planName = planName,
                    durationMinutes = durationMinutes,
                    rateKbit = rateKbit,
                    ceilKbit = ceilKbit
                )
            )
            codes.add(code)
        }
        return codes
    }

    private companion object {
        const val CLASS_ID_START = 100
        const val CODE_RETRY_LIMIT = 10
    }
}
