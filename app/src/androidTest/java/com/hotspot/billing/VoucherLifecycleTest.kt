package com.hotspot.billing

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hotspot.billing.db.AppDatabase
import com.hotspot.billing.db.UserSession
import com.hotspot.billing.db.Voucher
import com.hotspot.billing.db.VoucherStatus
import com.hotspot.billing.net.IpPool
import com.hotspot.billing.net.LanPlan
import com.hotspot.billing.net.RedeemResult
import com.hotspot.billing.net.VoucherManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The billing lifecycle, end to end - the flow the operator runs all day:
 *
 *  1. a customer joins and APPLIES a voucher (redeem): the code is bound to
 *     their MAC, a static IP is assigned and the session is SAVED;
 *  2. the same device can re-connect with the same code, but nobody else can
 *     use it;
 *  3. the operator KICKS the device (the Users tab's kick button ->
 *     VoucherManager.forceExpire): rules, reservation and sessions go away and
 *     the code stops working;
 *  4. the operator APPOINTS A NEW VOUCHER to the device, which works again.
 *
 * Plus the two failure modes a customer would notice: a voucher that ran out
 * of time is swept and its sessions closed, and a full network reports
 * "network is full" instead of handing out an address that matches nobody.
 *
 * Runs against an in-memory copy of the real Room database, so the assertions
 * are about the real code paths (VoucherManager, IpPool, the DAOs) without
 * touching the app's own data.
 */
@RunWith(AndroidJUnit4::class)
class VoucherLifecycleTest {

    private val context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var db: AppDatabase
    private lateinit var vouchers: VoucherManager

    /** The customer's device, as the ARP resolver would report it. */
    private val customerMac = "aa:bb:cc:dd:ee:01"
    private val customerIp = "192.168.49.50"

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vouchers = VoucherManager(db)
        // IpPool is a process-wide singleton: start every test from the default
        // subnet with an empty pool (mirrors IpPoolTest.drainPool).
        IpPool.configure(LanPlan.DEFAULT)
        for (i in LanPlan.STATIC_START..LanPlan.STATIC_END) {
            IpPool.release("10.66.0.$i")
            IpPool.release("192.168.43.$i")
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun redeem_then_kick_then_appoint_a_new_voucher() {
        // --- the operator mints codes -------------------------------------
        val codes = vouchers.generateBatch(3, "1 Hour - 2/1 Mbps", 60, 2_000, 1_000)
        assertEquals(3, codes.size)
        codes.forEach { code ->
            val row = db.voucherDao().findByCode(code)
            assertNotNull("generated code $code must be in the database", row)
            assertEquals(VoucherStatus.UNUSED, row!!.status)
        }

        // --- 1. the customer APPLIES the voucher ---------------------------
        val applied = vouchers.redeem(codes[0], customerMac, customerIp)
        assertTrue("a fresh code must redeem: $applied", applied is RedeemResult.Success)

        val bound = db.voucherDao().findByCode(codes[0])!!
        assertEquals(VoucherStatus.ACTIVE, bound.status)
        assertEquals("the code locks to the first device that used it", customerMac, bound.boundMac)
        assertNotNull("a static IP is assigned", bound.assignedIp)
        assertNotNull("an expiry is recorded", bound.expiresAt)

        // --- the session is SAVED ------------------------------------------
        val openSessions = db.sessionDao().getOpenByMac(customerMac)
        assertTrue("the connection is recorded as an open session", openSessions.isNotEmpty())
        assertEquals(codes[0], openSessions.first().voucherCode)
        assertEquals(customerIp, openSessions.first().ip)

        // --- 2. the same device re-connects; nobody else can ----------------
        val again = vouchers.redeem(codes[0], customerMac, customerIp)
        assertTrue("the same device may re-submit the same code", again is RedeemResult.Success)

        val otherDevice = vouchers.redeem(codes[0], "aa:bb:cc:dd:ee:99", "192.168.49.77")
        assertTrue(
            "the code must not work on a second device",
            otherDevice is RedeemResult.AlreadyUsedOnAnotherDevice
        )

        // --- 3. the operator KICKS the device -------------------------------
        assertTrue(vouchers.forceExpire(codes[0]))
        val kicked = db.voucherDao().findByCode(codes[0])!!
        assertEquals(VoucherStatus.EXPIRED, kicked.status)
        assertTrue(
            "kicking a device closes its open sessions",
            db.sessionDao().getOpenByMac(customerMac).isEmpty()
        )

        val afterKick = vouchers.redeem(codes[0], customerMac, customerIp)
        assertTrue("a kicked code stops working", afterKick is RedeemResult.Expired)

        // --- 4. the operator APPOINTS A NEW VOUCHER to the device -----------
        val fresh = vouchers.generateBatch(1, "1 Hour - 2/1 Mbps", 60, 2_000, 1_000)
        val reapplied = vouchers.redeem(fresh[0], customerMac, customerIp)
        assertTrue("the device gets back online with the new voucher", reapplied is RedeemResult.Success)

        val newBound = db.voucherDao().findByCode(fresh[0])!!
        assertEquals(VoucherStatus.ACTIVE, newBound.status)
        assertEquals(customerMac, newBound.boundMac)
        assertTrue(
            "the new appointment opens a session again",
            db.sessionDao().getOpenByMac(customerMac).isNotEmpty()
        )

        // the untouched codes are still on sale
        assertEquals(VoucherStatus.UNUSED, db.voucherDao().findByCode(codes[1])!!.status)
        assertEquals(VoucherStatus.UNUSED, db.voucherDao().findByCode(codes[2])!!.status)
    }

    @Test
    fun a_voucher_that_runs_out_of_time_is_swept_and_its_sessions_closed() {
        val codes = vouchers.generateBatch(1, "1 Hour - 2/1 Mbps", 60, 2_000, 1_000)
        val now = System.currentTimeMillis()

        // A live voucher whose time is already up, with a connected client.
        db.voucherDao().upsert(
            Voucher(
                code = codes[0],
                planName = "1 Hour - 2/1 Mbps",
                durationMinutes = 60,
                rateKbit = 2_000,
                ceilKbit = 1_000,
                status = VoucherStatus.ACTIVE,
                boundMac = customerMac,
                assignedIp = "10.66.0.30",
                activatedAt = now - 120 * 60_000L,
                expiresAt = now - 60_000L,
                classId = 101
            )
        )
        db.sessionDao().insert(
            UserSession(
                mac = customerMac,
                ip = customerIp,
                voucherCode = codes[0],
                connectedAt = now - 120 * 60_000L
            )
        )

        vouchers.sweepExpired()

        assertEquals(
            VoucherStatus.EXPIRED,
            db.voucherDao().findByCode(codes[0])!!.status
        )
        assertTrue(
            "the sweep closes the client's session",
            db.sessionDao().getOpenByMac(customerMac).isEmpty()
        )
        assertTrue(
            "the expired code is refused afterwards",
            vouchers.redeem(codes[0], customerMac, customerIp) is RedeemResult.Expired
        )
    }

    @Test
    fun a_full_network_is_reported_instead_of_handing_out_a_bad_address() {
        // Fill the static pool (.10-.49) the way 40 sold-out vouchers would.
        val taken = (LanPlan.STATIC_START..LanPlan.STATIC_END).map { IpPool.allocate() }
        assertFalse("the pool must have handed out every address", taken.any { it == null })

        val codes = vouchers.generateBatch(1, "1 Hour - 2/1 Mbps", 60, 2_000, 1_000)
        val result = vouchers.redeem(codes[0], customerMac, customerIp)
        assertTrue(
            "with no address left the redeem must report a full network",
            result is RedeemResult.PoolExhausted
        )
        assertEquals(
            "the voucher must stay on sale, not half-activated",
            VoucherStatus.UNUSED,
            db.voucherDao().findByCode(codes[0])!!.status
        )
    }
}
