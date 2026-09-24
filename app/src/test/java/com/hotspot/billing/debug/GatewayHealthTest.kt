package com.hotspot.billing.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayHealthTest {

    /** A gateway that is doing its job: nothing to report. */
    private fun healthy() = HealthInput(
        lanIf = "ap0",
        lanInterfaceExists = true,
        lanAddresses = listOf("192.168.43.1/24"),
        expectedGateway = "192.168.43.1",
        ipForwardEnabled = true,
        ourDnsmasqRunning = true,
        foreignDnsmasqRunning = false,
        dhcpOwner = "ours",
        portalAlive = true,
        portalProbeStatus = 200,
        natJumpFirst = true,
        forwardJumpFirst = true,
        wanIf = "ccmni0",
        defaultRouteIf = "ccmni0",
        policyRoutingOk = true,
        connectedClients = 3,
        authorizedClients = 1,
        apKind = "Android hotspot"
    )

    private fun codes(findings: List<Finding>) = findings.map { it.code }

    @Test
    fun `a healthy gateway reports nothing`() {
        assertEquals(emptyList<String>(), codes(GatewayHealth.evaluate(healthy())))
        assertNull(GatewayHealth.headline(GatewayHealth.evaluate(healthy())))
    }

    @Test
    fun `H1 when the AP interface disappears, and nothing else is evaluated`() {
        val findings = GatewayHealth.evaluate(healthy().copy(lanInterfaceExists = false))
        assertEquals(listOf("H1"), codes(findings))
        assertEquals(LogLevel.ERROR, findings.first().level)
        assertTrue(findings.first().hint.contains("NetShare"))
    }

    @Test
    fun `H3 when no DHCP server is running - the Obtaining IP case`() {
        val findings = GatewayHealth.evaluate(
            healthy().copy(ourDnsmasqRunning = false, dhcpOwner = "failed")
        )
        assertTrue(codes(findings).contains("H3"))
        assertTrue(findings.first { it.code == "H3" }.problem.contains("Obtaining IP"))
    }

    @Test
    fun `Android's own DHCP server counts as a working DHCP server`() {
        val findings = GatewayHealth.evaluate(
            healthy().copy(ourDnsmasqRunning = false, foreignDnsmasqRunning = true, dhcpOwner = "android")
        )
        assertTrue(codes(findings).isEmpty())
    }

    @Test
    fun `the DNS-only mode counts too (system DHCP, our resolver)`() {
        val findings = GatewayHealth.evaluate(
            healthy().copy(ourDnsmasqRunning = true, dhcpOwner = "ours-dns")
        )
        assertTrue(codes(findings).isEmpty())
    }

    @Test
    fun `H4 when IP forwarding got switched back off`() {
        assertTrue(codes(GatewayHealth.evaluate(healthy().copy(ipForwardEnabled = false))).contains("H4"))
        // Unknown (could not be read) must not raise an alarm.
        assertTrue(codes(GatewayHealth.evaluate(healthy().copy(ipForwardEnabled = null))).isEmpty())
    }

    @Test
    fun `H8 when the routing rule a self-managed AP needs is missing`() {
        val findings = GatewayHealth.evaluate(healthy().copy(policyRoutingOk = false))
        assertTrue(codes(findings).contains("H8"))
        assertTrue(findings.first { it.code == "H8" }.hint.contains("ip rule add"))
    }

    @Test
    fun `H5 when the portal is down and H10 when it answers with the wrong status`() {
        assertTrue(codes(GatewayHealth.evaluate(healthy().copy(portalAlive = false))).contains("H5"))
        assertTrue(
            codes(GatewayHealth.evaluate(healthy().copy(portalProbeStatus = 302))).contains("H10")
        )
    }

    @Test
    fun `H7 when the phone itself lost its internet side`() {
        assertTrue(codes(GatewayHealth.evaluate(healthy().copy(defaultRouteIf = null))).contains("H7"))
        assertTrue(
            codes(GatewayHealth.evaluate(healthy().copy(wanIf = "ccmni0", defaultRouteIf = "wlan0")))
                .contains("H7")
        )
    }

    @Test
    fun `H6 when Android pushed our firewall jump out of first place`() {
        assertTrue(codes(GatewayHealth.evaluate(healthy().copy(natJumpFirst = false))).contains("H6"))
        assertTrue(codes(GatewayHealth.evaluate(healthy().copy(forwardJumpFirst = false))).contains("H6"))
    }

    @Test
    fun `H2 when the interface address moved away from the gateway we serve`() {
        val findings = GatewayHealth.evaluate(healthy().copy(lanAddresses = listOf("192.168.50.1/24")))
        assertTrue(codes(findings).contains("H2"))
    }

    @Test
    fun `errors are reported before warnings, so the headline is the worst one`() {
        val findings = GatewayHealth.evaluate(
            healthy().copy(ipForwardEnabled = false, natJumpFirst = false, portalProbeStatus = 302)
        )
        assertEquals(LogLevel.ERROR, findings.first().level)
        assertEquals("H4", GatewayHealth.headline(findings)?.substringBefore(' '))
    }

    @Test
    fun `every finding carries a fix, not just a complaint`() {
        val findings = GatewayHealth.evaluate(
            healthy().copy(
                lanInterfaceExists = true,
                ourDnsmasqRunning = false,
                foreignDnsmasqRunning = false,
                dhcpOwner = "failed",
                portalAlive = false,
                ipForwardEnabled = false,
                policyRoutingOk = false,
                natJumpFirst = false
            )
        )
        assertTrue(findings.size >= 4)
        findings.forEach {
            assertTrue("${it.code} has no hint", it.hint.isNotBlank())
            assertTrue("${it.code} has no code prefix", it.format().startsWith(it.code))
        }
    }
}
