package com.hotspot.billing.net

import com.hotspot.billing.db.AppDatabase
import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.debug.LogFormat
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

    /**
     * Last time each code had its rules applied, so a client that re-submits the
     * same voucher (reconnect, browser retry, the user pressing Connect twice)
     * does not run the whole reserve + authorize + tc sequence again.
     *
     * The 2026-09-24 debug log shows four `POST /redeem` for one voucher inside
     * 25 s, each one repeating ~6 shell commands - 6 s of root shell time, on the
     * same shell the DHCP server is being managed from.
     */
    private val lastAppliedAt = HashMap<String, Long>()

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
            voucher.assignedIp?.let { if (IpPool.inStaticPool(it)) IpPool.markUsed(it) }
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
    fun redeem(code: String, requestingMac: String, currentIp: String? = null): RedeemResult {
        ensureRestored()

        val normalized = VoucherCodes.normalize(code)
        val voucher = db.voucherDao().findByCode(normalized)
        if (voucher == null) {
            AppLog.w(
                AppLog.TAG_VOUCHER,
                "redeem rejected: code \"$normalized\" does not exist (mac $requestingMac, ip ${currentIp ?: "?"})"
            )
            return RedeemResult.InvalidCode
        }

        val now = System.currentTimeMillis()
        AppLog.i(
            AppLog.TAG_VOUCHER,
            "redeem \"$normalized\" (${voucher.planName}, ${voucher.status}) from mac $requestingMac" +
                " ip ${currentIp ?: "?"}"
        )

        val result = when (voucher.status) {
            VoucherStatus.UNUSED -> activateFresh(voucher, requestingMac, now, currentIp)

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
                    // of just reporting success. Pass the address it holds right now
                    // so it is not blackholed until DHCP hands it the reserved one.
                    else -> {
                        val sinceApplied = now - (lastAppliedAt[voucher.code] ?: 0L)
                        if (sinceApplied < REAPPLY_COOLDOWN_MS) {
                            AppLog.i(
                                AppLog.TAG_VOUCHER,
                                "redeem \"${voucher.code}\" already applied ${sinceApplied}ms ago " +
                                    "for $boundMac - not re-running the firewall/TC commands"
                            )
                        } else {
                            applyAccess(
                                boundMac, ip, currentIp, voucher.classId,
                                voucher.rateKbit, voucher.ceilKbit
                            )
                            lastAppliedAt[voucher.code] = now
                        }
                        IpPool.markUsed(ip)
                        db.sessionDao().insert(
                            UserSession(
                                mac = boundMac,
                                ip = currentIp?.takeIf { it.isNotBlank() } ?: ip,
                                voucherCode = voucher.code,
                                connectedAt = now
                            )
                        )
                        RedeemResult.Success(ip, expiresAt)
                    }
                }
            }

            VoucherStatus.EXPIRED -> RedeemResult.Expired
        }
        AppLog.i(
            AppLog.TAG_VOUCHER,
            "redeem \"$normalized\" -> ${result.javaClass.simpleName}" +
                if (result is RedeemResult.Success) " ip=${result.ip} expires=${LogFormat.timestamp(result.expiresAt)}" else ""
        )
        return result
    }

    private fun activateFresh(
        voucher: Voucher,
        mac: String,
        now: Long,
        currentIp: String?
    ): RedeemResult {
        val ip = IpPool.allocate()
        if (ip == null) {
            AppLog.e(
                AppLog.TAG_VOUCHER,
                "no static IP left in ${IpPool.currentPlan().subnet} - the pool (.10-.49) is exhausted"
            )
            return RedeemResult.PoolExhausted
        }
        val expiresAt = now + voucher.durationMinutes * 60_000L
        val classId = classIdCounter.incrementAndGet()
        AppLog.i(
            AppLog.TAG_VOUCHER,
            "activating \"${voucher.code}\" on mac $mac -> ip $ip, tc class 1:$classId " +
                "(${voucher.rateKbit}/${voucher.ceilKbit} kbit), expires ${LogFormat.timestamp(expiresAt)}"
        )

        val updated = voucher.copy(
            status = VoucherStatus.ACTIVE,
            boundMac = mac,
            assignedIp = ip,
            activatedAt = now,
            expiresAt = expiresAt,
            classId = classId
        )
        db.voucherDao().upsert(updated)

        applyAccess(mac, ip, currentIp, classId, voucher.rateKbit, voucher.ceilKbit)
        lastAppliedAt[voucher.code] = now

        db.sessionDao().insert(
            UserSession(
                mac = mac,
                ip = currentIp?.takeIf { it.isNotBlank() } ?: ip,
                voucherCode = voucher.code,
                connectedAt = now
            )
        )

        return RedeemResult.Success(ip, expiresAt)
    }

    /**
     * Firewall match is the MAC (the client is often still on a dynamic lease).
     * The reserved IP is pushed via DHCP when we own the server, and both
     * addresses are shaped so the cap applies before the lease migrates.
     */
    private fun applyAccess(
        mac: String,
        reservedIp: String,
        currentIp: String?,
        classId: Int?,
        rateKbit: Int,
        ceilKbit: Int
    ) {
        RootShell.reserveIp(mac, reservedIp)
        RootShell.authorizeMac(mac, reservedIp, currentIp)
        if (classId == null) return
        RootShell.addBandwidthClass(reservedIp, classId, rateKbit, ceilKbit)
        val extra = currentIp?.takeIf { it.isNotBlank() && it != reservedIp }
        if (extra != null && classId <= CLASS_ID_TRANSITIONAL_MAX) {
            RootShell.addBandwidthClass(extra, classId + CLASS_ID_TRANSITIONAL, rateKbit, ceilKbit)
        }
    }

    private fun expireVoucher(voucher: Voucher) {
        AppLog.i(
            AppLog.TAG_VOUCHER,
            "expiring \"${voucher.code}\" (mac ${voucher.boundMac ?: "-"}, ip ${voucher.assignedIp ?: "-"}) " +
                "- removing the firewall ACCEPT, the DHCP reservation and the tc class"
        )
        db.voucherDao().upsert(voucher.copy(status = VoucherStatus.EXPIRED))
        voucher.boundMac?.let {
            RootShell.deauthorizeMac(it)
            RootShell.releaseIp(it)
        }
        voucher.assignedIp?.let { ip ->
            IpPool.release(ip)
            voucher.classId?.let { classId ->
                RootShell.removeBandwidthClass(ip, classId)
                if (classId <= CLASS_ID_TRANSITIONAL_MAX) {
                    RootShell.removeBandwidthByClass(classId + CLASS_ID_TRANSITIONAL)
                }
            }
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
        val expired = db.voucherDao().getExpired(now)
        if (expired.isNotEmpty()) {
            AppLog.i(AppLog.TAG_VOUCHER, "sweep: ${expired.size} voucher(s) reached their expiry")
        }
        expired.forEach { expireVoucher(it) }
    }

    /**
     * Admin action: end a voucher right now, whatever state it is in. Kicks the
     * client off the network (rules + reservation + shaping) and closes its sessions.
     */
    @Synchronized
    fun forceExpire(code: String): Boolean {
        ensureRestored()
        val voucher = db.voucherDao().findByCode(VoucherCodes.normalize(code)) ?: return false
        AppLog.i(AppLog.TAG_VOUCHER, "operator forced \"$code\" to expire (was ${voucher.status})")
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
        AppLog.i(AppLog.TAG_VOUCHER, "operator deleted voucher \"$code\" (was ${voucher.status})")
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
        val online = RootShell.connectedClients(null)
        val active = db.voucherDao().getActive()
        AppLog.i(
            AppLog.TAG_VOUCHER,
            "re-applying ${active.size} active voucher(s) (${online.size} device(s) currently on the LAN)"
        )
        active.forEach { voucher ->
            val mac = voucher.boundMac ?: return@forEach
            var ip = voucher.assignedIp
            // A static from a previous subnet (10.66.0.x after we adopted
            // 192.168.43.0/24) matches nobody. Hand out a new one in-subnet.
            if (ip == null || !IpPool.inStaticPool(ip)) {
                ip?.let { IpPool.release(it) }
                ip = IpPool.allocate() ?: return@forEach
                db.voucherDao().upsert(voucher.copy(assignedIp = ip))
            }
            val current = online.firstOrNull { it.mac == mac.lowercase() }?.ip
            applyAccess(mac, ip, current, voucher.classId, voucher.rateKbit, voucher.ceilKbit)
            IpPool.markUsed(ip)
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
        AppLog.i(
            AppLog.TAG_VOUCHER,
            "generated ${codes.size} voucher(s) for \"$planName\" " +
                "(${durationMinutes}min, ${rateKbit}/${ceilKbit} kbit)"
        )
        return codes
    }

    private companion object {
        const val CLASS_ID_START = 100
        const val CODE_RETRY_LIMIT = 10
        /** Offset for the cap on the address the client still holds. Stays inside tc's 16-bit class id. */
        const val CLASS_ID_TRANSITIONAL = 20_000
        const val CLASS_ID_TRANSITIONAL_MAX = 45_000

        /** Re-submitting the same voucher within this window re-applies nothing. */
        const val REAPPLY_COOLDOWN_MS = 20_000L
    }
}
