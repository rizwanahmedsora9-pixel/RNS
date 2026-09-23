package com.hotspot.billing.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Small human-friendly formatters shared by the admin UI lists. */
object UiFmt {

    fun minutes(totalMinutes: Int): String {
        val d = totalMinutes / 1440
        val h = (totalMinutes % 1440) / 60
        val m = totalMinutes % 60
        val parts = mutableListOf<String>()
        if (d > 0) parts.add("$d d")
        if (h > 0) parts.add("$h h")
        if (m > 0 || parts.isEmpty()) parts.add("$m min")
        return parts.joinToString(" ")
    }

    fun remaining(expiresAt: Long?): String {
        if (expiresAt == null) return ""
        val ms = expiresAt - System.currentTimeMillis()
        if (ms <= 0) return "expired"
        return minutes((ms / 60_000L).toInt() + 1) + " left"
    }

    fun time(ts: Long?): String =
        if (ts == null || ts <= 0) "?"
        else SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(ts))
}
