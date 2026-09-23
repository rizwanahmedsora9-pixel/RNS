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
    fun `escapes error text`() {
        assertTrue(PortalPages.errorHtml("<script>").contains("&lt;script&gt;"))
    }
}
