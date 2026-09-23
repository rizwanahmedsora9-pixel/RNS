package com.hotspot.billing.portal

import com.hotspot.billing.net.ArpResolver
import com.hotspot.billing.net.RedeemResult
import com.hotspot.billing.net.VoucherManager
import fi.iki.elonen.NanoHTTPD

/**
 * Listens on 0.0.0.0:8080. iptables REDIRECTs unauthenticated TCP/80 on the
 * hotspot interface here, which is what makes phones and PCs pop the
 * "sign in to network" sheet.
 *
 * The probe URL itself is answered with the login page (HTTP 200, not a
 * redirect to :8080). A redirect to a high port, or a connection timeout,
 * does not count as a captive portal on current Android/iOS — the sheet
 * never appears and the client just sits there with an IP and no internet.
 *
 * HTTPS is deliberately NOT redirected here. scripts/setup_network.sh resets
 * port 443 so the client falls back to its plain-HTTP probe.
 */
class CaptivePortalServer(
    private val voucherManager: VoucherManager,
    private val gatewayIp: String = DEFAULT_GATEWAY,
    port: Int = PORT,
    private val log: (String) -> Unit = {}
) : NanoHTTPD(port) {

    private val seen = HashMap<String, Long>()

    // Probes send Accept-Encoding: gzip. A gzipped "200" is a failed probe on
    // several Android builds (timeout / no sheet), not a captive portal.
    override fun useGzipWhenAccepted(r: Response): Boolean = false

    override fun serve(session: IHTTPSession): Response {
        note(session)
        val clientIp = session.remoteIpAddress.orEmpty()

        return when {
            session.method == Method.POST && session.uri == "/redeem" ->
                handleRedeem(session, clientIp)

            // Probe or any other GET: the login page, status 200. Returning 204
            // (or redirecting to :8080) is what makes the OS decide there is
            // nothing to sign in to.
            else -> page(Response.Status.OK, PortalPages.loginHtml())
        }
    }

    private fun note(session: IHTTPSession) {
        val ip = session.remoteIpAddress ?: "?"
        val key = "$ip ${session.uri}"
        val now = System.currentTimeMillis()
        synchronized(seen) {
            val prev = seen[key] ?: 0L
            if (now - prev < LOG_INTERVAL_MS) return
            seen[key] = now
        }
        val kind = if (PortalPages.isProbe(session.uri)) "probe" else "request"
        log("portal $kind ${session.method} ${session.uri} from $ip (gateway $gatewayIp)")
    }

    private fun handleRedeem(session: IHTTPSession, clientIp: String): Response {
        val files = HashMap<String, String>()
        session.parseBody(files)
        val code = session.parms["voucher"]?.trim().orEmpty()
        if (code.isEmpty()) return page(Response.Status.OK, PortalPages.errorHtml("Missing voucher code"))

        val mac = ArpResolver.macForIp(clientIp)
            ?: return page(
                Response.Status.OK,
                PortalPages.errorHtml("Could not identify your device. Reconnect to the WiFi and try again.")
            )

        return when (val result = voucherManager.redeem(code, mac, clientIp)) {
            is RedeemResult.Success -> page(Response.Status.OK, PortalPages.successHtml(result.expiresAt))
            RedeemResult.InvalidCode -> page(Response.Status.OK, PortalPages.errorHtml("Invalid voucher code."))
            RedeemResult.AlreadyUsedOnAnotherDevice ->
                page(Response.Status.OK, PortalPages.errorHtml("This voucher is already in use on another device."))
            RedeemResult.Expired -> page(Response.Status.OK, PortalPages.errorHtml("This voucher has expired."))
            RedeemResult.PoolExhausted -> page(Response.Status.OK, PortalPages.errorHtml("Network is full. Try again shortly."))
        }
    }

    private fun page(status: Response.Status, body: String): Response {
        val resp = newFixedLengthResponse(status, "text/html; charset=utf-8", body)
        // Captive-portal detectors cache aggressively and treat a kept-alive
        // socket that never closes as a failed probe (no sheet). The server
        // writes Connection from these flags, so a header alone is not enough.
        resp.setKeepAlive(false)
        resp.closeConnection(true)
        resp.setGzipEncoding(false)
        resp.addHeader("Cache-Control", "no-store, no-cache, must-revalidate")
        resp.addHeader("Pragma", "no-cache")
        resp.addHeader("Expires", "0")
        return resp
    }

    companion object {
        const val PORT = 8080
        const val DEFAULT_GATEWAY = "10.66.0.1"
        private const val LOG_INTERVAL_MS = 20_000L
    }
}
