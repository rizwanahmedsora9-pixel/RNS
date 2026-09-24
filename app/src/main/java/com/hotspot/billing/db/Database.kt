package com.hotspot.billing.db

import android.content.Context
import androidx.room.*

@Dao
interface VoucherDao {
    @Query("SELECT * FROM vouchers WHERE code = :code LIMIT 1")
    fun findByCode(code: String): Voucher?

    @Query("SELECT * FROM vouchers WHERE boundMac = :mac AND status = 'ACTIVE' LIMIT 1")
    fun findActiveByMac(mac: String): Voucher?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(voucher: Voucher)

    @Query("SELECT * FROM vouchers ORDER BY createdAt DESC")
    fun getAll(): List<Voucher>

    @Query("SELECT * FROM vouchers WHERE status = 'ACTIVE'")
    fun getActive(): List<Voucher>

    @Query("SELECT * FROM vouchers WHERE status = 'ACTIVE' AND expiresAt < :now")
    fun getExpired(now: Long): List<Voucher>

    @Query("DELETE FROM vouchers WHERE code = :code")
    fun delete(code: String)

    /** Highest tc class id handed out so far; the allocator resumes above this after a restart. */
    @Query("SELECT MAX(classId) FROM vouchers")
    fun maxClassId(): Int?
}

@Dao
interface SessionDao {
    @Insert
    fun insert(session: UserSession): Long

    @Query("UPDATE sessions SET disconnectedAt = :ts, bytesUp = :up, bytesDown = :down WHERE id = :id")
    fun close(id: Long, ts: Long, up: Long, down: Long)

    @Query("SELECT * FROM sessions WHERE mac = :mac AND disconnectedAt IS NULL")
    fun getOpenByMac(mac: String): List<UserSession>

    @Query("SELECT * FROM sessions ORDER BY connectedAt DESC LIMIT :limit")
    fun getRecent(limit: Int): List<UserSession>

    @Query("SELECT * FROM sessions ORDER BY connectedAt DESC")
    fun getAll(): List<UserSession>
}

@Dao
interface DeviceProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(profile: DeviceProfile)

    @Query("SELECT * FROM device_profiles ORDER BY lastSeen DESC")
    fun getAll(): List<DeviceProfile>

    @Query("SELECT * FROM device_profiles WHERE mac = :mac LIMIT 1")
    fun findByMac(mac: String): DeviceProfile?

    @Query("DELETE FROM device_profiles WHERE mac = :mac")
    fun delete(mac: String)
}

@Dao
interface DhcpLeaseDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(lease: DhcpLease)

    @Query("SELECT * FROM dhcp_leases ORDER BY lastSeen DESC")
    fun getAll(): List<DhcpLease>

    @Query("SELECT * FROM dhcp_leases WHERE mac = :mac LIMIT 1")
    fun findByMac(mac: String): DhcpLease?

    @Query("SELECT * FROM dhcp_leases WHERE ip = :ip LIMIT 1")
    fun findByIp(ip: String): DhcpLease?

    @Query("DELETE FROM dhcp_leases WHERE mac = :mac")
    fun deleteByMac(mac: String)

    @Query("DELETE FROM dhcp_leases")
    fun clearAll()
}

@Dao
interface HotspotSessionDao {
    @Insert
    fun insert(session: HotspotSession): Long

    @Query("UPDATE hotspot_sessions SET stop = :stop, status = :status, dataUsed = :dataUsed WHERE id = :id")
    fun close(id: Long, stop: Long, status: String, dataUsed: Long)

    @Query("SELECT * FROM hotspot_sessions ORDER BY start DESC LIMIT :limit")
    fun getRecent(limit: Int): List<HotspotSession>

    @Query("SELECT * FROM hotspot_sessions ORDER BY start DESC")
    fun getAll(): List<HotspotSession>

    @Query("SELECT * FROM hotspot_sessions WHERE stop IS NULL LIMIT 1")
    fun getActive(): HotspotSession?
}

@Dao
interface ClientDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(client: Client)

    @Query("SELECT * FROM clients ORDER BY lastSeen DESC")
    fun getAll(): List<Client>

    @Query("SELECT * FROM clients WHERE mac = :mac LIMIT 1")
    fun findByMac(mac: String): Client?

    @Query("DELETE FROM clients WHERE mac = :mac")
    fun delete(mac: String)

    @Query("UPDATE clients SET downloadBytes = :down, uploadBytes = :up, lastSeen = :lastSeen WHERE mac = :mac")
    fun updateUsage(mac: String, down: Long, up: Long, lastSeen: Long)

    @Query("UPDATE clients SET isBlocked = :blocked WHERE mac = :mac")
    fun setBlocked(mac: String, blocked: Boolean)
}

@Dao
interface PaymentDao {
    @Insert
    fun insert(payment: Payment): Long

    @Query("SELECT * FROM payments ORDER BY date DESC")
    fun getAll(): List<Payment>

    @Query("SELECT * FROM payments WHERE voucherId = :voucherId")
    fun getByVoucher(voucherId: String): List<Payment>

    @Query("SELECT SUM(amount) FROM payments")
    fun getTotalRevenue(): Double?
}

@Dao
interface SettingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(setting: Setting)

    @Query("SELECT * FROM settings WHERE `key` = :key LIMIT 1")
    fun findByKey(key: String): Setting?

    @Query("SELECT * FROM settings")
    fun getAll(): List<Setting>

    @Query("DELETE FROM settings WHERE `key` = :key")
    fun delete(key: String)
}

@Dao
interface VoucherPlanDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(plan: VoucherPlan)

    @Query("SELECT * FROM voucher_plans ORDER BY price ASC")
    fun getAll(): List<VoucherPlan>

    @Query("SELECT * FROM voucher_plans WHERE id = :id LIMIT 1")
    fun findById(id: Long): VoucherPlan?

    @Query("DELETE FROM voucher_plans WHERE id = :id")
    fun delete(id: Long)
}

@Database(
    entities = [Voucher::class, UserSession::class, DeviceProfile::class,
        DhcpLease::class, HotspotSession::class, Client::class,
        Payment::class, Setting::class, VoucherPlan::class],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun voucherDao(): VoucherDao
    abstract fun sessionDao(): SessionDao
    abstract fun deviceProfileDao(): DeviceProfileDao
    abstract fun dhcpLeaseDao(): DhcpLeaseDao
    abstract fun hotspotSessionDao(): HotspotSessionDao
    abstract fun clientDao(): ClientDao
    abstract fun paymentDao(): PaymentDao
    abstract fun settingDao(): SettingDao
    abstract fun voucherPlanDao(): VoucherPlanDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "hotspot_billing.db"
                )
                    // v1 -> v2 adds the device_profiles table. No device in the field
                    // holds data worth a hand-written migration yet (there was no UI
                    // to create vouchers with), so a rebuild is the safe choice.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
