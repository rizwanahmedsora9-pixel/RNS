package com.hotspot.billing.portal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortalPagesTest {

    @Test
    fun `recognises the probes that decide whether to show the sign-in sheet`() {
        assertTrue(PortalPages.isProbe("/generate_204"))
        assertTrue(PortalPages.isProbe("/gen_204"))
        assertTrue(PortalPages.isProbe("/hotspot-detect.html"))
        assertTrue(PortalPages.isProbe("/library/test/success.html"))
        assertTrue(PortalPages.isProbe("/ncsi.txt"))
        assertTrue(PortalPages.isProbe("/connecttest.txt"))
        assertFalse(PortalPages.isProbe("/redeem"))
    }

    @Test
    fun `login page is a same-origin form, not a redirect to port 8080`() {
        val html = PortalPages.loginHtml()
        assertTrue(html.contains("action=\"/redeem\""))
        assertTrue(html.contains("Sign in"))
        assertFalse(html.contains(":8080"))
        assertFalse(html.contains("10.66.0.1"))
        // iOS treats a body of exactly "Success" as "internet works" and stays silent.
        assertFalse(html.contains(">Success<"))
    }

    @Test
    fun `login page carries the one-tap join card when the credentials are known`() {
        val html = PortalPages.loginHtml("DIRECT-RNSCafe", "rns-open-2026", "BASE64PNG")
        assertTrue(html.contains("DIRECT-RNSCafe"))
        assertTrue(html.contains("rns-open-2026"))
        assertTrue(html.contains("data:image/png;base64,BASE64PNG"))
    }

    @Test
    fun `login page shows the credentials as text when no QR is available`() {
        val html = PortalPages.loginHtml("DIRECT-RNSCafe", "rns-open-2026", null)
        assertTrue(html.contains("DIRECT-RNSCafe"))
        assertTrue(html.contains("rns-open-2026"))
        assertFalse(html.contains("<img"))
    }

    @Test
    fun `login page has no join card without credentials`() {
        val html = PortalPages.loginHtml()
        assertFalse(html.contains("<img"))
        assertFalse(html.contains("No WiFi yet"))
    }

    @Test
    fun `escapes error text`() {
        assertTrue(PortalPages.errorHtml("<script>").contains("&lt;script&gt;"))
    }

    @Test
    fun `escapes the join card text`() {
        val html = PortalPages.loginHtml("<b>EVIL</b>", "p&ass", null)
        assertTrue(html.contains("&lt;b&gt;EVIL&lt;/b&gt;"))
        assertTrue(html.contains("p&amp;ass"))
        assertFalse(html.contains("<b>EVIL</b>"))
    }
}
