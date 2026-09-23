package com.hotspot.billing.db

import androidx.room.TypeConverter

/**
 * Room cannot persist Kotlin enums without a converter - without this class kapt
 * fails with "Cannot figure out how to save this field into database".
 *
 * Values are stored as their name (UNUSED / ACTIVE / EXPIRED) so the raw SQL in
 * the DAOs can keep matching on the literal strings.
 */
class Converters {

    @TypeConverter
    fun statusToString(status: VoucherStatus?): String? = status?.name

    @TypeConverter
    fun stringToStatus(value: String?): VoucherStatus? =
        value?.let { raw -> VoucherStatus.values().firstOrNull { it.name == raw } }
}
