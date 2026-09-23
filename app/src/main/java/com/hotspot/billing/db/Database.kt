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

    @Query("SELECT * FROM sessions ORDER BY connectedAt DESC")
    fun getAll(): List<UserSession>
}

@Database(
    entities = [Voucher::class, UserSession::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun voucherDao(): VoucherDao
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext, AppDatabase::class.java, "hotspot_billing.db"
                ).build().also { INSTANCE = it }
            }
    }
}
