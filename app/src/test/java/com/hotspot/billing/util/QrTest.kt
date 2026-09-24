package com.hotspot.billing.util

import com.google.zxing.common.BitMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrTest {

    private val payload = "WIFI:T:WPA;S:DIRECT-RNS;P=rns-open-2026;;"

    @Test
    fun `the join payload encodes to a square matrix at the requested size`() {
        val m = Qr.matrix(payload, 101)
        assertNotNull("the QR writer should accept the WIFI payload", m)
        assertEquals(101, m!!.width)
        assertEquals(101, m.height)
    }

    // Bounding box of the dark modules: the quiet zone is white, so the box's
    // corners are the finder-pattern corners.
    private fun bounds(m: BitMatrix): IntArray {
        var minX = m.width; var minY = m.height; var maxX = -1; var maxY = -1
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m.get(x, y)) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }
        return intArrayOf(minX, minY, maxX, maxY)
    }

    /** Length of the run of [dark] pixels starting at (x, y), stepping (dx, dy). */
    private fun run(m: BitMatrix, x: Int, y: Int, dark: Boolean, dx: Int, dy: Int): Int {
        var i = 0
        while (true) {
            val nx = x + i * dx
            val ny = y + i * dy
            if (nx < 0 || ny < 0 || nx >= m.width || ny >= m.height) break
            if (m.get(nx, ny) != dark) break
            i++
        }
        return i
    }

    @Test
    fun `the matrix carries the three finder patterns`() {
        val m = Qr.matrix(payload, 101)!!
        val (minX, minY, maxX, maxY) = bounds(m)
        // Top-left corner: 7 dark modules, a 1-module gap, then the timing
        // pattern (1 dark module). With non-integer scaling each module is
        // ~3-4 px, so compare ratios instead of exact counts.
        val l1 = run(m, minX, minY, dark = true, dx = 1, dy = 0)      // ~7 modules
        val l2 = run(m, minX + l1, minY, dark = false, dx = 1, dy = 0) // ~1
        val l3 = run(m, minX + l1 + l2, minY, dark = true, dx = 1, dy = 0) // ~1
        val u = l1 / 7.0
        assertTrue("gap $l2 vs unit $u", l2.toDouble() in 0.6 * u..1.4 * u)
        assertTrue("timing start $l3 vs unit $u", l3.toDouble() in 0.6 * u..1.4 * u)
        // The other two corners of the bounding box carry the same 7-module
        // dark run in both directions.
        assertTrue("top-left column", run(m, minX, minY, dark = true, dx = 0, dy = 1).toDouble() in 5.6 * u..8.4 * u)
        assertTrue("top-right row", run(m, maxX, minY, dark = true, dx = -1, dy = 0).toDouble() in 5.6 * u..8.4 * u)
        assertTrue("top-right column", run(m, maxX, minY, dark = true, dx = 0, dy = 1).toDouble() in 5.6 * u..8.4 * u)
        assertTrue("bottom-left row", run(m, minX, maxY, dark = true, dx = 1, dy = 0).toDouble() in 5.6 * u..8.4 * u)
        assertTrue("bottom-left column", run(m, minX, maxY, dark = true, dx = 0, dy = -1).toDouble() in 5.6 * u..8.4 * u)
    }

    @Test
    fun `an empty payload is refused rather than rendered`() {
        assertNull(Qr.matrix("", 101))
    }

    @Test
    fun `different payloads produce different matrices`() {
        val a = Qr.matrix("WIFI:T:WPA;S:DIRECT-AAA;P=rns-open-2026;;", 65)!!
        val b = Qr.matrix("WIFI:T:WPA;S:DIRECT-BBB;P=rns-open-2026;;", 65)!!
        var same = true
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (a.get(x, y) != b.get(x, y)) same = false
        }
        assertFalse("two different SSIDs must not share a QR", same)
    }
}
