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

@Database(
    entities = [Voucher::class, UserSession::class, DeviceProfile::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun voucherDao(): VoucherDao
    abstract fun sessionDao(): SessionDao
    abstract fun deviceProfileDao(): DeviceProfileDao

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
