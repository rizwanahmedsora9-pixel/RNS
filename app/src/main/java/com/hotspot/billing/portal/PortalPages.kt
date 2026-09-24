package com.hotspot.billing.portal

/**
 * Captive-portal HTML, kept free of Android and NanoHTTPD so it can be tested.
 *
 * The page is what the phone itself returns for the OS probe (generate_204,
 * hotspot-detect, ncsi). It must be a normal HTTP 200 with a body — a redirect
 * to http://10.66.0.1:8080 does not pop the sign-in sheet on current Android
 * or iOS, and a private-IP redirect is ignored by several OEM builds.
 *
 * The form posts back to the same host (`/redeem`). iptables has already
 * redirected that host's port 80 onto the portal, so the browser never needs
 * to know the gateway address or the high port.
 */
object PortalPages {

    fun isProbe(uri: String): Boolean {
        val path = uri.lowercase()
        return path.contains("generate_204") ||
            path.contains("gen_204") ||
            path.contains("generate204") ||
            path.contains("hotspot-detect") ||
            path.contains("success.html") ||
            path.contains("ncsi.txt") ||
            path.contains("connecttest") ||
            path.contains("canonical.html") ||
            path.contains("/redirect")
    }

    /**
     * The sign-in page. When [ssid]/[pass] are known they are shown together
     * with the one-tap join QR - the passphrase is a fixed, non-secret
     * technicality, the voucher is the real gate. [qrBase64] is a PNG data
     * URI; when it is null the credentials are still shown as text.
     */
    fun loginHtml(ssid: String? = null, pass: String? = null, qrBase64: String? = null): String {
        val joinCard = if (ssid != null && pass != null) {
            val qr = if (qrBase64 != null) """
        <img src="data:image/png;base64,$qrBase64" width="150" height="150"
             style="background:#fff;padding:6px;border-radius:8px;margin-top:10px">"""
            else ""
            """
        <div style="margin-top:24px;padding:14px;border:1px solid #333;border-radius:10px;max-width:340px;margin-left:auto;margin-right:auto">
            <p style="margin:0 0 6px 0;font-size:13px;color:#9ab">No WiFi yet? Join this network:</p>
            <p style="margin:0;font-size:15px"><b>${escapeHtml(ssid)}</b></p>
            <p style="margin:2px 0 0 0;font-size:13px;color:#9ab">password: <code>${escapeHtml(pass)}</code></p>
            $qr
        </div>"""
        } else ""
        return """
        <!DOCTYPE html>
        <html><head>
        <meta charset="utf-8">
        <title>Sign in</title>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
        body{font-family:sans-serif;text-align:center;padding:32px 16px;background:#111;color:#eee}
        input{padding:12px;font-size:18px;width:80%;max-width:280px;border-radius:8px;border:none;margin-top:16px;text-align:center}
        button{padding:12px 24px;font-size:18px;border-radius:8px;border:none;background:#2d8cf0;color:#fff;margin-top:16px}
        </style></head>
        <body>
        <h2>Sign in to network</h2>
        <p>Enter your voucher code to connect</p>
        <form method="POST" action="/redeem">
            <input type="text" name="voucher" placeholder="XXXX-XXXX" autocapitalize="characters" autocomplete="off" required>
            <br><button type="submit">Connect</button>
        </form>
        $joinCard
        </body></html>
    """.trimIndent()
    }

    /**
     * After the voucher is accepted the client's MAC skips the redirect, so this
     * refresh hits the real probe URL and the OS closes the sign-in sheet.
     */
    fun successHtml(expiresAt: Long): String = """
        <!DOCTYPE html>
        <html><head>
        <meta charset="utf-8">
        <meta http-equiv="refresh" content="2;url=http://connectivitycheck.gstatic.com/generate_204">
        <title>Connected</title>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        </head>
        <body style="font-family:sans-serif;text-align:center;padding-top:40px">
        <h2>Connected</h2>
        <p>Your session expires at ${java.util.Date(expiresAt)}.</p>
        <p>You can close this window.</p>
        </body></html>
    """.trimIndent()

    fun errorHtml(message: String): String = """
        <!DOCTYPE html>
        <html><head><meta charset="utf-8"><title>Sign in</title>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        </head>
        <body style="font-family:sans-serif;text-align:center;padding-top:40px;color:#c0392b">
        <h3>${escapeHtml(message)}</h3>
        <p><a href="/">Back</a></p>
        </body></html>
    """.trimIndent()

    fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
