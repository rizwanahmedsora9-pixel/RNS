package com.hotspot.billing

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hotspot.billing.db.AppDatabase
import com.hotspot.billing.net.VoucherManager
import com.hotspot.billing.portal.CaptivePortalServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.HttpURLConnection
import java.net.URL

/**
 * The captive portal over real HTTP - the layer a customer's phone actually
 * talks to when they join the network.
 *
 * "Confirm user connectivity" means this: the moment a client connects, its OS
 * asks a probe URL (generate_204 and friends). Answering that with a plain
 * HTTP 200 carrying the sign-in page is what makes Android/iOS/Windows pop the
 * "sign in to network" sheet; a redirect to a high port or a timeout leaves the
 * user with an IP and no way in. That exact behaviour is asserted here against
 * a live NanoHTTPD instance.
 *
 * The redemption path itself is covered by VoucherLifecycleTest at the manager
 * level: the portal resolves the client's MAC from the ARP table, which only
 * exists once a real client is on the LAN (no emulator can fake that).
 * Everything else - routing, pages, error handling - is the real code.
 */
@RunWith(AndroidJUnit4::class)
class PortalEndToEndTest {

    @Test
    fun captive_portal_answers_the_connectivity_probe_with_the_sign_in_page() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        var portal: CaptivePortalServer? = null
        try {
            portal = CaptivePortalServer(
                voucherManager = VoucherManager(db),
                gatewayIp = "192.168.49.1",
                port = E2E.freePort()
            ) { }
            portal.start()

            // 1. The OS probe: what every phone/PC asks the instant it joins.
            val probe = http("GET", port = localPort(portal), path = "/generate_204")
            assertEquals("the probe URL must answer 200 (a redirect means no sign-in sheet)", 200, probe.first)
            assertTrue("the probe answer must be the sign-in page", probe.second.contains("Sign in to network"))
            assertTrue("the page must offer the voucher form", probe.second.contains("action=\"/redeem\""))

            // 2. The other probe URLs current OS builds use.
            for (probePath in listOf(
                "/connecttest.txt",
                "/ncsi.txt",
                "/hotspot-detect.html",
                "/success.html"
            )) {
                val other = http("GET", port = localPort(portal), path = probePath)
                assertEquals("$probePath must answer 200", 200, other.first)
                assertTrue("$probePath must carry the sign-in page", other.second.contains("Sign in to network"))
            }

            // 3. A submission that cannot work must come back as a page with a
            //    reason, not a dropped connection or a crash.
            val bad = http("POST", port = localPort(portal), path = "/redeem", body = "voucher=NOPE-1234")
            assertEquals("a failed redemption must still answer 200", 200, bad.first)
            val body = bad.second.lowercase()
            assertTrue(
                "the error page must explain the failure, got: ${bad.second.take(200)}",
                body.contains("could not identify") ||
                    body.contains("invalid voucher") ||
                    body.contains("network is full") ||
                    body.contains("expired") ||
                    body.contains("another device")
            )
        } finally {
            try {
                portal?.stop()
            } catch (e: Throwable) {
                // socket already gone
            }
            db.close()
        }
    }

    /** Read back the port NanoHTTPD actually bound to. */
    private fun localPort(portal: CaptivePortalServer): Int =
        portal.listeningPort.takeIf { it > 0 } ?: error("the portal never started listening")

    private fun http(method: String, port: Int, path: String, body: String? = null): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        return try {
            val code = connection.responseCode
            val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.readText()
                ?: ""
            code to text
        } finally {
            connection.disconnect()
        }
    }
}
