package com.hotspot.billing.core

import com.hotspot.billing.debug.AppLog

/**
 * Phase 9 — Printing
 * Support: Bluetooth thermal printer, USB printer
 *
 * Print format per master plan:
 * ================
 * RNS INTERNET
 * CODE: ABC123
 * TIME: 2 HOURS
 * DATA: 1GB
 * PRICE: 100
 * ================
 */
object PrinterManager {

    data class VoucherPrintData(
        val code: String,
        val planName: String,
        val durationText: String,
        val dataLimitText: String,
        val priceText: String,
        val ssid: String? = null
    )

    fun formatVoucher(printData: VoucherPrintData): String {
        return buildString {
            appendLine("================")
            appendLine("RNS INTERNET")
            appendLine("================")
            appendLine()
            appendLine("CODE: ${printData.code}")
            appendLine()
            appendLine("TIME: ${printData.durationText}")
            appendLine("DATA: ${printData.dataLimitText}")
            appendLine("PRICE: ${printData.priceText}")
            if (printData.ssid != null) {
                appendLine()
                appendLine("WiFi: ${printData.ssid}")
            }
            appendLine()
            appendLine("================")
            appendLine("Connect to WiFi")
            appendLine("and enter code")
            appendLine("================")
        }
    }

    fun formatBatch(codes: List<VoucherPrintData>): String {
        return codes.joinToString("\n\n") { formatVoucher(it) }
    }

    /**
     * Bluetooth printing — placeholder for actual BT implementation
     * Will need BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_CONNECT permissions
     * and using BluetoothSocket to send ESC/POS commands
     */
    fun printViaBluetooth(printData: VoucherPrintData): Boolean {
        AppLog.i(AppLog.TAG_BILLING, "printer: BT print requested for ${printData.code} (not yet implemented, need BT device)")
        // TODO: Implement Bluetooth printing
        // 1. Get BluetoothAdapter
        // 2. Find paired thermal printer
        // 3. Open BluetoothSocket
        // 4. Send ESC/POS bytes
        return false
    }

    fun printViaUsb(printData: VoucherPrintData): Boolean {
        AppLog.i(AppLog.TAG_BILLING, "printer: USB print requested for ${printData.code} (not yet implemented)")
        // TODO: USB printer via UsbManager
        return false
    }

    /**
     * Generate shareable text for voucher (works without printer)
     */
    fun generateShareText(printData: VoucherPrintData): String {
        return formatVoucher(printData)
    }

    fun durationToText(minutes: Int): String {
        return when {
            minutes < 60 -> "$minutes MIN"
            minutes == 60 -> "1 HOUR"
            minutes % 60 == 0 -> "${minutes / 60} HOURS"
            minutes == 1440 -> "1 DAY"
            minutes % 1440 == 0 -> "${minutes / 1440} DAYS"
            else -> {
                val h = minutes / 60
                val m = minutes % 60
                if (m == 0) "$h HOURS" else "$h H ${m}M"
            }
        }
    }

    fun dataToText(mb: Int): String {
        return when {
            mb < 1024 -> "${mb}MB"
            mb % 1024 == 0 -> "${mb / 1024}GB"
            else -> "${mb}MB (${mb / 1024.0}GB)"
        }
    }
}
