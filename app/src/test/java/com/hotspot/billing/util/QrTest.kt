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
        // Top-left corner: a 7-module dark run, a 1-module gap, then the
        // timing pattern. Non-integer scaling smears exact module counts, so
        // the check is qualitative: the gap is at most a third of the finder
        // run, and all five corner runs are of the same size as each other.
        val l1 = run(m, minX, minY, dark = true, dx = 1, dy = 0)       // ~7 modules
        val l2 = run(m, minX + l1, minY, dark = false, dx = 1, dy = 0)  // ~1 module
        val l3 = run(m, minX + l1 + l2, minY, dark = true, dx = 1, dy = 0) // ~1 module
        assertTrue("gap ($l2) must be far shorter than the finder run ($l1)", l1 >= 2 * l2)
        assertTrue("timing start ($l3) must be far shorter than the finder run ($l1)", l1 >= 2 * l3)
        val corners = listOf(
            "top-left column" to run(m, minX, minY, dark = true, dx = 0, dy = 1),
            "top-right row" to run(m, maxX, minY, dark = true, dx = -1, dy = 0),
            "top-right column" to run(m, maxX, minY, dark = true, dx = 0, dy = 1),
            "bottom-left row" to run(m, minX, maxY, dark = true, dx = 1, dy = 0),
            "bottom-left column" to run(m, minX, maxY, dark = true, dx = 0, dy = -1)
        )
        for ((label, r) in corners) {
            assertTrue("$label run $r vs top-left $l1", r in (l1 - 3)..(l1 + 3))
        }
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
