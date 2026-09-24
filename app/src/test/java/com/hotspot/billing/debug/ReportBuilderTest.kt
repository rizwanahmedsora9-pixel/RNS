package com.hotspot.billing.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportBuilderTest {

    @Test
    fun `header lines are aligned key-value pairs under a banner`() {
        val report = ReportBuilder("test report")
            .header(listOf("generated" to "now", "device" to "Infinix HOT 8"))
            .build()
        assertTrue(report.contains("test report"))
        assertTrue(report.contains("generated         : now"))
        assertTrue(report.contains("device            : Infinix HOT 8"))
        assertTrue(report.contains("############"))
    }

    @Test
    fun `sections are numbered in the order they are added`() {
        val report = ReportBuilder("r")
            .section("DEVICE")
            .section("FIREWALL")
            .build()
        assertTrue(report.contains("== 1. DEVICE =="))
        assertTrue(report.contains("== 2. FIREWALL =="))
    }

    @Test
    fun `a command result always shows its exit code and duration`() {
        val report = ReportBuilder("r")
            .command("iptables -S", 0, 12, "-P FORWARD DROP")
            .command("tc qdisc show", 2, 5, "", "RTNETLINK answers: No such file")
            .build()
        assertTrue(report.contains("-- iptables -S (exit 0, 12ms) --"))
        assertTrue(report.contains("    -P FORWARD DROP"))
        assertTrue(report.contains("-- tc qdisc show (exit 2, 5ms) --"))
        assertTrue(report.contains("stderr: RTNETLINK answers: No such file"))
    }

    @Test
    fun `an empty result says so instead of looking like a missing section`() {
        val report = ReportBuilder("r").command("ip rule show", 0, 1, "", "").build()
        assertTrue(report.contains("(no output)"))
    }

    @Test
    fun `a huge output is truncated with the dropped size reported`() {
        val huge = "line\n".repeat(20_000)
        val report = ReportBuilder("r").command("dumpsys wifi", 0, 900, huge).build()
        assertTrue(report.length < huge.length)
        assertTrue(report.contains("truncated"))
    }

    @Test
    fun `null and blank values are labelled, never printed as nothing`() {
        val report = ReportBuilder("r")
            .kv("gateway", null)
            .kv("ssid", "")
            .build()
        assertTrue(report.contains("(null)"))
        assertTrue(report.contains("(blank)"))
    }

    @Test
    fun `the report ends with a tally so a truncated paste is obvious`() {
        val report = ReportBuilder("r")
            .section("A").command("x", 0, 1, "y")
            .section("B")
            .build()
        assertTrue(report.contains("end of report (2 sections, 1 commands)"))
    }
}
