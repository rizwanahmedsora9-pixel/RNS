package com.hotspot.billing.debug

import java.net.HttpURLConnection
import java.net.URL

/** Result of one HTTP probe, in the shape the report and the watchdog want. */
data class ProbeResult(
    val status: Int?,
    val location: String?,
    val contentType: String?,
    val bodyHead: String,
    val millis: Long,
    val error: String?
) {
    val ok: Boolean get() = status == 200

    fun describe(): String = when {
        error != null -> "FAILED after ${millis}ms: $error"
        else -> buildString {
            append("HTTP ").append(status).append(" in ").append(millis).append("ms\n")
            append("location: ").append(location ?: "(none)").append('\n')
            append("content-type: ").append(contentType ?: "(none)").append('\n')
            append("body (first 400 chars): ").append(bodyHead.take(400).ifBlank { "(empty)" })
        }
    }
}

/**
 * The captive-portal probe, from the phone's own point of view.
 *
 * A client only shows the sign-in sheet when the probe comes back as HTTP 200
 * with a body - a 302 to another port, a 204 or a timeout all mean "this is not
 * a captive portal". Probing it ourselves is the difference between guessing and
 * knowing.
 */
object HttpProbe {

    fun get(url: String, timeoutMs: Int = 4_000): ProbeResult {
        val started = System.nanoTime()
        var conn: HttpURLConnection? = null
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.instanceFollowRedirects = false
            c.requestMethod = "GET"
            val code = c.responseCode
            val body = try {
                (if (code in 200..399) c.inputStream else c.errorStream)
                    ?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (e: Exception) {
                "(body unreadable: ${e.message})"
            }
            ProbeResult(
                status = code,
                location = c.getHeaderField("Location"),
                contentType = c.contentType,
                bodyHead = body.take(600),
                millis = ms(started),
                error = null
            )
        } catch (e: Exception) {
            ProbeResult(
                status = null,
                location = null,
                contentType = null,
                bodyHead = "",
                millis = ms(started),
                error = "${e.javaClass.simpleName}: ${e.message}"
            )
        } finally {
            try {
                conn?.disconnect()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun ms(startedNanos: Long) = (System.nanoTime() - startedNanos) / 1_000_000
}
