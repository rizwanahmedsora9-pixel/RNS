package com.hotspot.billing.util

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.io.ByteArrayOutputStream

/**
 * QR rendering for the one-tap join code (SSID + fixed passphrase).
 *
 * [matrix] is pure zxing and unit-testable; the Bitmap/PNG helpers are thin
 * Android wrappers around it.
 */
object Qr {

    /**
     * The QR bit matrix for [payload] at exactly [modules] x [modules], or null
     * when it cannot be encoded. zxing's own encoder scales the version's
     * natural size by an integer factor only (a 33-module version at "101px"
     * comes back 99x99), so the result is nearest-neighbour scaled to the
     * requested square - the finder patterns are intact.
     */
    fun matrix(payload: String, modules: Int = 333): BitMatrix? = try {
        val hints = HashMap<EncodeHintType, Any>()
        hints[EncodeHintType.MARGIN] = 2
        val raw = MultiFormatWriter().encode(payload, BarcodeFormat.QR_CODE, modules, modules, hints)
        if (raw.width == modules && raw.height == modules) raw else scaleTo(raw, modules)
    } catch (e: Throwable) {
        null
    }

    /** Nearest-neighbour scale to [target] x [target], centred. */
    private fun scaleTo(src: BitMatrix, target: Int): BitMatrix {
        val out = BitMatrix(target, target)
        val offX = (target - src.width) / 2
        val offY = (target - src.height) / 2
        for (y in 0 until src.height) {
            val ty0 = offY + y * target / src.height
            val ty1 = offY + (y + 1) * target / src.height
            for (x in 0 until src.width) {
                if (!src.get(x, y)) continue
                val tx0 = offX + x * target / src.width
                val tx1 = offX + (x + 1) * target / src.width
                for (ty in ty0 until ty1) {
                    for (tx in tx0 until tx1) {
                        out.set(tx, ty)
                    }
                }
            }
        }
        return out
    }

    /** A square black-on-white bitmap for [payload] at [sizePx] x [sizePx]. */
    fun bitmap(payload: String, sizePx: Int): Bitmap? {
        val m = matrix(payload, sizePx) ?: return null
        val w = m.width
        val h = m.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                pixels[y * w + x] = if (m.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }

    /** PNG bytes for [payload]; null when it cannot be encoded. */
    fun pngBytes(payload: String, sizePx: Int = 480): ByteArray? {
        val bmp = bitmap(payload, sizePx) ?: return null
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        bmp.recycle()
        return out.toByteArray()
    }

    /** Base64 (no line wraps) PNG - for embedding in the portal's HTML. */
    fun base64Png(payload: String, sizePx: Int = 480): String? =
        pngBytes(payload, sizePx)?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
}
