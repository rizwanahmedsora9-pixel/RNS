package com.hotspot.billing.core

import com.hotspot.billing.db.AppDatabase
import com.hotspot.billing.db.Payment
import com.hotspot.billing.db.Voucher
import com.hotspot.billing.db.VoucherPlan
import com.hotspot.billing.db.VoucherStatus
import com.hotspot.billing.debug.AppLog
import com.hotspot.billing.net.VoucherManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phase 8 — Voucher System + Billing
 * Enhanced voucher handling with data_limit, price, payments
 *
 * Features:
 *  Generate: RNS-1001, RNS-1002
 *  Rules: Time (1h, 5h, 1d), Data (500MB, 1GB, 5GB)
 *  Actions: Activate, Expire, Disable, Print
 */
class BillingManager(
    private val db: AppDatabase,
    private val voucherManager: VoucherManager
) {

    data class VoucherWithPlan(
        val code: String,
        val planName: String,
        val durationMinutes: Int,
        val dataLimitMb: Int,
        val price: Double,
        val rateKbit: Int,
        val ceilKbit: Int
    )

    // Predefined plans per master plan
    val defaultPlans = listOf(
        VoucherPlan(name = "1 Hour", durationMinutes = 60, dataLimitMb = 500, price = 50.0, rateKbit = 1024, ceilKbit = 2048),
        VoucherPlan(name = "3 Hours", durationMinutes = 180, dataLimitMb = 1024, price = 100.0, rateKbit = 2048, ceilKbit = 4096),
        VoucherPlan(name = "5 Hours", durationMinutes = 300, dataLimitMb = 2048, price = 150.0, rateKbit = 2048, ceilKbit = 4096),
        VoucherPlan(name = "1 Day", durationMinutes = 1440, dataLimitMb = 5120, price = 250.0, rateKbit = 4096, ceilKbit = 8192),
        VoucherPlan(name = "7 Days", durationMinutes = 10080, dataLimitMb = 10240, price = 500.0, rateKbit = 4096, ceilKbit = 8192)
    )

    suspend fun ensureDefaultPlans() = withContext(Dispatchers.IO) {
        val existing = db.voucherPlanDao().getAll()
        if (existing.isEmpty()) {
            defaultPlans.forEach { plan ->
                db.voucherPlanDao().upsert(plan)
            }
            AppLog.i(AppLog.TAG_BILLING, "billing: seeded ${defaultPlans.size} default plans")
        }
    }

    suspend fun generateVouchersFromPlan(planId: Long, count: Int): List<String> = withContext(Dispatchers.IO) {
        val plan = db.voucherPlanDao().findById(planId)
            ?: throw IllegalArgumentException("Plan $planId not found")

        val codes = voucherManager.generateBatch(count, plan.name, plan.durationMinutes, plan.rateKbit, plan.ceilKbit)

        // Create payments placeholder? Actually payment created on activation
        AppLog.i(AppLog.TAG_BILLING, "billing: generated $count vouchers for plan ${plan.name}")
        codes
    }

    suspend fun generateCustomVoucher(
        planName: String,
        durationMinutes: Int,
        dataLimitMb: Int,
        price: Double,
        rateKbit: Int,
        ceilKbit: Int,
        count: Int = 1
    ): List<String> = withContext(Dispatchers.IO) {
        // Save as custom plan
        val plan = VoucherPlan(
            name = planName,
            durationMinutes = durationMinutes,
            dataLimitMb = dataLimitMb,
            price = price,
            rateKbit = rateKbit,
            ceilKbit = ceilKbit
        )
        db.voucherPlanDao().upsert(plan)

        val codes = voucherManager.generateBatch(count, planName, durationMinutes, rateKbit, ceilKbit)
        AppLog.i(AppLog.TAG_BILLING, "billing: generated $count custom vouchers $planName ${dataLimitMb}MB ${price}Rs")
        codes
    }

    suspend fun recordPayment(voucherCode: String, amount: Double, method: String = "CASH"): Long = withContext(Dispatchers.IO) {
        val payment = Payment(
            voucherId = voucherCode,
            amount = amount,
            method = method
        )
        val id = db.paymentDao().insert(payment)
        AppLog.i(AppLog.TAG_BILLING, "billing: payment recorded voucher=$voucherCode amount=$amount method=$method")
        id
    }

    suspend fun getTotalRevenue(): Double = withContext(Dispatchers.IO) {
        db.paymentDao().getTotalRevenue() ?: 0.0
    }

    suspend fun getVoucherDataLimit(voucherCode: String): Int? = withContext(Dispatchers.IO) {
        val voucher = db.voucherDao().findByCode(voucherCode) ?: return@withContext null
        // Find plan by name matching voucher planName
        val plans = db.voucherPlanDao().getAll()
        plans.firstOrNull { it.name == voucher.planName }?.dataLimitMb
    }

    suspend fun checkDataLimitExceeded(mac: String): Boolean = withContext(Dispatchers.IO) {
        val voucher = db.voucherDao().findActiveByMac(mac) ?: return@withContext false
        val client = db.clientDao().findByMac(mac) ?: return@withContext false
        val dataLimitMb = getVoucherDataLimit(voucher.code) ?: return@withContext false

        val usedMb = (client.downloadBytes + client.uploadBytes) / (1024 * 1024)
        usedMb >= dataLimitMb
    }
}
