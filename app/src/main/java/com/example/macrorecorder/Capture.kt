package com.example.macrorecorder

import android.graphics.Bitmap
import kotlin.math.abs

/** Holds the latest screen frame (filled by CaptureService) and color-search helpers. */
object Capture {
    val lock = Any()

    @Volatile var bitmap: Bitmap? = null
    @Volatile var width = 0
    @Volatile var height = 0

    data class Hit(val x: Int, val y: Int, val count: Int)

    fun clear() = synchronized(lock) { bitmap = null }

    /** Color (0xRRGGBB) at a screen position, or null if no frame / out of range. */
    fun pixel(x: Int, y: Int): Int? = synchronized(lock) {
        val b = bitmap ?: return null
        if (x < 0 || y < 0 || x >= width || y >= height) return null
        b.getPixel(x, y) and 0xFFFFFF
    }

    fun match(color: Int, rgb: Int, tol: Int): Boolean =
        abs(((color shr 16) and 255) - ((rgb shr 16) and 255)) <= tol &&
            abs(((color shr 8) and 255) - ((rgb shr 8) and 255)) <= tol &&
            abs((color and 255) - (rgb and 255)) <= tol

    /** Finds pixels near [rgb] inside the region; returns their average position. */
    fun scan(
        rgb: Int, tol: Int,
        x1: Int = 0, y1: Int = 0, x2: Int = Int.MAX_VALUE, y2: Int = Int.MAX_VALUE
    ): Hit? = synchronized(lock) {
        val b = bitmap ?: return null
        if (width <= 0 || height <= 0) return null
        val ax1 = minOf(x1, x2).coerceIn(0, width - 1)
        val ax2 = maxOf(x1, x2).coerceIn(0, width - 1)
        val ay1 = minOf(y1, y2).coerceIn(0, height - 1)
        val ay2 = maxOf(y1, y2).coerceIn(0, height - 1)
        val rw = ax2 - ax1 + 1
        val rh = ay2 - ay1 + 1
        if (rw <= 0 || rh <= 0) return null

        val px = IntArray(rw * rh)
        b.getPixels(px, 0, rw, ax1, ay1, rw, rh)

        var sx = 0L; var sy = 0L; var n = 0
        var yy = 0
        while (yy < rh) {
            var xx = 0
            while (xx < rw) {
                if (match(px[yy * rw + xx] and 0xFFFFFF, rgb, tol)) {
                    sx += xx + ax1; sy += yy + ay1; n++
                }
                xx += 2
            }
            yy += 2
        }
        if (n < 3) null else Hit((sx / n).toInt(), (sy / n).toInt(), n)
    }
}
