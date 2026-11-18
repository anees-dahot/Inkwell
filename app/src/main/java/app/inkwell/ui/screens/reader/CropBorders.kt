package app.inkwell.ui.screens.reader

import android.graphics.Bitmap
import coil3.size.Size
import coil3.transform.Transformation
import kotlin.math.abs

/**
 * Trims uniform white/black margins around a scanned page.
 *
 * Samples the corner colour, then walks in from each edge while the row/column stays within
 * [TOLERANCE] of it (allowing a small fraction of noisy pixels). Never crops more than
 * [MAX_FRACTION] of either dimension so a mostly-blank page isn't reduced to a sliver.
 */
object CropBorders : Transformation() {
    override val cacheKey = "crop-borders-v1"
    private const val TOLERANCE = 28
    private const val NOISE = 0.01f
    private const val MAX_FRACTION = 0.3f

    override suspend fun transform(input: Bitmap, size: Size): Bitmap {
        val w = input.width; val h = input.height
        if (w < 32 || h < 32) return input
        val bmp = if (input.config == Bitmap.Config.HARDWARE) input.copy(Bitmap.Config.ARGB_8888, false) else input
        val ref = bmp.getPixel(2, 2)
        if (!isPlain(ref)) return input
        val row = IntArray(w); val col = IntArray(h)
        fun rowBlank(y: Int): Boolean { bmp.getPixels(row, 0, w, 0, y, w, 1); return blank(row, ref) }
        fun colBlank(x: Int): Boolean { bmp.getPixels(col, 0, 1, x, 0, 1, h); return blank(col, ref) }

        val maxY = (h * MAX_FRACTION).toInt(); val maxX = (w * MAX_FRACTION).toInt()
        var top = 0; while (top < maxY && rowBlank(top)) top++
        var bottom = h - 1; while (h - 1 - bottom < maxY && bottom > top && rowBlank(bottom)) bottom--
        var left = 0; while (left < maxX && colBlank(left)) left++
        var right = w - 1; while (w - 1 - right < maxX && right > left && colBlank(right)) right--

        // keep a hair of margin so art isn't flush against the screen edge
        val pad = (minOf(w, h) * 0.005f).toInt()
        top = (top - pad).coerceAtLeast(0); left = (left - pad).coerceAtLeast(0)
        bottom = (bottom + pad).coerceAtMost(h - 1); right = (right + pad).coerceAtMost(w - 1)
        val cw = right - left + 1; val ch = bottom - top + 1
        if (cw >= w - 4 && ch >= h - 4) return input
        return Bitmap.createBitmap(bmp, left, top, cw, ch)
    }

    private fun isPlain(c: Int): Boolean {
        val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
        val lum = (r + g + b) / 3
        return (lum > 220 || lum < 35) && abs(r - g) < 20 && abs(g - b) < 20
    }

    private fun blank(px: IntArray, ref: Int): Boolean {
        val rr = (ref shr 16) and 0xFF; val rg = (ref shr 8) and 0xFF; val rb = ref and 0xFF
        var bad = 0
        val limit = (px.size * NOISE).toInt().coerceAtLeast(1)
        for (c in px) {
            val d = abs(((c shr 16) and 0xFF) - rr) + abs(((c shr 8) and 0xFF) - rg) + abs((c and 0xFF) - rb)
            if (d > TOLERANCE * 3 && ++bad > limit) return false
        }
        return true
    }
}
