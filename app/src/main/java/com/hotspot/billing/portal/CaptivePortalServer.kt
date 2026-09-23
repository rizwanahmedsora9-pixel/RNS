package com.hotspot.billing.portal

import com.hotspot.billing.net.ArpResolver
import com.hotspot.billing.net.RedeemResult
import com.hotspot.billing.net.VoucherManager
import fi.iki.elonen.NanoHTTPD

/**
 * Listens on the LAN IP (10.66.0.1:8080). iptables DNATs un-authenticated HTTP on
 * the LAN interface here, which is what makes phones/PCs pop the "sign in to
 * network" sheet.
 *
 * HTTPS is deliberately NOT redirected here - see scripts/setup_network.sh, which
 * resets port 443 instead so clients fall back to their plain-HTTP probe.
 */
class CaptivePortalServer(
    private val voucherManager: VoucherManager,
    port: Int = PORT
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        // Only the real socket peer is trusted. A client-supplied header would let
        // anyone claim to be a different LAN IP and redeem against its MAC.
        val clientIp = session.remoteIpAddress

        return when {
            // OS captive-portal probe endpoints. Anything that is not the exact
            // expected body counts as "portal detected", so a redirect works.
            session.uri.contains("generate_204") ||            // Android
            session.uri.contains("gen_204") -> redirectToPortal()

            session.uri.contains("hotspot-detect.html") ||     // iOS / macOS
            session.uri.contains("library/test/success.html") -> redirectToPortal()

            session.uri.contains("ncsi.txt") ||                // Windows
            session.uri.contains("connecttest.txt") -> redirectToPortal()

            session.method == Method.POST && session.uri == "/redeem" ->
                handleRedeem(session, clientIp)

            else -> newFixedLengthResponse(Response.Status.OK, "text/html", loginPage())
        }
    }

    private fun redirectToPortal(): Response {
        val resp = newFixedLengthResponse(Response.Status.REDIRECT, "text/html", "")
        resp.addHeader("Location", "http://$GATEWAY_IP:$PORT/")
        return resp
    }

    private fun handleRedeem(session: IHTTPSession, clientIp: String): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val code = session.parms["voucher"]?.trim().orEmpty()
        if (code.isEmpty()) return errorPage("Missing voucher code")

        val mac = ArpResolver.macForIp(clientIp)
            ?: return errorPage("Could not identify your device. Reconnect to the WiFi and try again.")

        return when (val result = voucherManager.redeem(code, mac)) {
            is RedeemResult.Success -> successPage(result.expiresAt)
            RedeemResult.InvalidCode -> errorPage("Invalid voucher code.")
            RedeemResult.AlreadyUsedOnAnotherDevice ->
                errorPage("This voucher is already in use on another device.")
            RedeemResult.Expired -> errorPage("This voucher has expired.")
            RedeemResult.PoolExhausted -> errorPage("Network is full. Try again shortly.")
        }
    }

    private fun loginPage() = """
        <html><head><title>WiFi Login</title>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>body{font-family:sans-serif;text-align:center;padding-top:40px;background:#111;color:#eee}
        input{padding:12px;font-size:18px;width:80%;max-width:300px;border-radius:8px;border:none;margin-top:20px;text-align:center}
        button{padding:12px 24px;font-size:18px;border-radius:8px;border:none;background:#2d8cf0;color:#fff;margin-top:16px}
        </style></head>
        <body>
        <h2>Welcome</h2>
        <p>Enter your voucher code to connect</p>
        <form method="POST" action="/redeem">
            <input type="text" name="voucher" placeholder="XXXX-XXXX" autocapitalize="characters" required>
            <br><button type="submit">Connect</button>
        </form>
        </body></html>
    """.trimIndent()

    private fun successPage(expiresAt: Long) = newFixedLengthResponse(
        Response.Status.OK, "text/html",
        "<html><body style='font-family:sans-serif;text-align:center;padding-top:40px'>" +
            "<h2>Connected!</h2><p>Your session expires at ${java.util.Date(expiresAt)}</p></body></html>"
    )

    private fun errorPage(msg: String) = newFixedLengthResponse(
        Response.Status.OK, "text/html",
        "<html><body style='font-family:sans-serif;text-align:center;padding-top:40px;color:#c0392b'>" +
            "<h3>${escapeHtml(msg)}</h3><a href='/'>Back</a></body></html>"
    )

    private fun escapeHtml(value: String) = value
        .replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;")

    companion object {
        const val PORT = 8080
        const val GATEWAY_IP = "10.66.0.1"
    }
}
