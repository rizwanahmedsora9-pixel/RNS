package com.hotspot.billing.net

/**
 * The customer-facing WiFi credentials.
 *
 * The passphrase is deliberately **fixed and non-secret**. WiFi Direct (the only
 * way this app creates the network) requires WPA, so the network can never be
 * literally open — but making the passphrase a guessable constant means joining
 * is a single tap (scan the QR / read it off the kiosk screen), not a typed
 * password step. The voucher is the real gate; the passphrase is a technical
 * formality, which is why it is shown openly on the dashboard, the portal and
 * in the QR code instead of being protected.
 */
object JoinConfig {

    /** 13 printable ASCII characters - valid WPA2-PSK, and never rotated. */
    const val FIXED_PASSPHRASE = "rns-open-2026"

    /**
     * Standard `WIFI:` QR payload (NFC/QR spec) that most phones understand,
     * so the customer's device joins with one scan. `;`, `,`, `:` and `\`
     * inside a field must be backslash-escaped; SSIDs we create are
     * `DIRECT-<alphanumerics>` but the escaping is kept for safety.
     */
    fun wifiQrPayload(ssid: String): String =
        "WIFI:T:WPA;S:${qrEscape(ssid)};P:$FIXED_PASSPHRASE;;"

    private fun qrEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace(":", "\\:")

    /** The SSID the group is requested with: `DIRECT-` + printable alphanumerics. */
    fun requestedSsid(preferred: String?): String =
        ApConfigText.normalizeDirectSsid(preferred)
}
