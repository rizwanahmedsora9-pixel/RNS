package com.hotspot.billing.net

import kotlin.random.Random

/**
 * Voucher code generation. Pure JVM (no Android types) so it is unit-testable.
 * Alphabet deliberately drops 0/O and 1/I so codes stay readable when typed in
 * by hand on the captive portal.
 */
object VoucherCodes {

    const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val BLOCK_LENGTH = 4
    const val FORMAT_REGEX = "[$ALPHABET]{4}-[$ALPHABET]{4}"

    fun generate(random: Random = Random.Default): String {
        fun block() = (1..BLOCK_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
        return "${block()}-${block()}"
    }

    /** Normalises user input: trims, uppercases, and re-inserts a missing dash. */
    fun normalize(input: String): String {
        val compact = input.trim().uppercase().replace("-", "").replace(" ", "")
        return if (compact.length == BLOCK_LENGTH * 2) {
            compact.substring(0, BLOCK_LENGTH) + "-" + compact.substring(BLOCK_LENGTH)
        } else {
            compact
        }
    }
}
