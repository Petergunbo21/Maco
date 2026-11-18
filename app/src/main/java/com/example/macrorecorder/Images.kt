package com.example.macrorecorder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/** Saved picture templates for the findimg command, plus the matcher that finds them on screen. */
object Images {

    class Template(val w: Int, val h: Int, val px: IntArray)

    class Hit(val x: Int, val y: Int)

    private var screenBuf: IntArray? = null

    private fun dir(c: Context): File = File(c.filesDir, "images").apply { mkdirs() }

    /**
     * Saves a box of the current screen as a PNG.
     * Returns the name (without .png) to use in findimg, or null if the box is too small or there is no frame.
     */
    fun saveCrop(c: Context, x1: Int, y1: Int, x2: Int, y2: Int): String? {
        val left = minOf(x1, x2)
        val top = minOf(y1, y2)
        val w = Math.abs(x2 - x1)
        val h = Math.abs(y2 - y1)
        if (w < 8 || h < 8) return null

        val crop: Bitmap = synchronized(Capture.lock) {
            val b = Capture.bitmap ?: return null
            val l = left.coerceIn(0, Capture.width - 1)
            val t = top.coerceIn(0, Capture.height - 1)
            val cw = minOf(w, Capture.width - l)
            val ch = minOf(h, Capture.height - t)
            if (cw < 8 || ch < 8) return null
            Bitmap.createBitmap(b, l, t, cw, ch)
        }

        val name = "img_${System.currentTimeMillis() % 100000}"
        FileOutputStream(File(dir(c), "$name.png")).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        crop.recycle()
        return name
    }

    /** Saves a box of a frozen screenshot (used by the Fisch setup). Returns the name, or null if too small. */
    fun saveFrom(c: Context, src: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int): String? {
        val l = minOf(x1, x2).coerceIn(0, src.width - 1)
        val t = minOf(y1, y2).coerceIn(0, src.height - 1)
        val w = minOf(Math.abs(x2 - x1), src.width - l)
        val h = minOf(Math.abs(y2 - y1), src.height - t)
        if (w < 8 || h < 8) return null
        val crop = Bitmap.createBitmap(src, l, t, w, h)
        val name = "img_${System.currentTimeMillis() % 100000}"
        FileOutputStream(File(dir(c), "$name.png")).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return name
    }

    /** Loads a saved picture by name (without .png). Returns null if it doesn't exist. */
    fun load(c: Context, name: String): Template? {
        val f = File(dir(c), "$name.png")
        if (!f.exists()) return null
        val bmp = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (i in px.indices) px[i] = px[i] and 0xFFFFFF
        val t = Template(bmp.width, bmp.height, px)
        bmp.recycle()
        return t
    }

    /**
     * Looks for the picture on the current screen frame.
     * Compares a sampled grid of pixels; a match allows up to 10% of samples to differ.
     */
    fun find(t: Template, tol: Int): Hit? {
        val stepT = 4   // template sample spacing
        val stepS = 3   // screen search spacing

        val sx = ArrayList<Int>()
        val sy = ArrayList<Int>()
        var yy = 0
        while (yy < t.h) {
            var xx = 0
            while (xx < t.w) {
                sx.add(xx); sy.add(yy)
                xx += stepT
            }
            yy += stepT
        }
        val n = sx.size
        val allowed = n / 10

        synchronized(Capture.lock) {
            val b = Capture.bitmap ?: return null
            val W = Capture.width
            val H = Capture.height
            if (W < t.w || H < t.h) return null

            var screen = screenBuf
            if (screen == null || screen.size != W * H) {
                screen = IntArray(W * H)
                screenBuf = screen
            }
            b.getPixels(screen, 0, W, 0, 0, W, H)

            var y = 0
            while (y <= H - t.h) {
                var x = 0
                while (x <= W - t.w) {
                    var bad = 0
                    var k = 0
                    while (k < n) {
                        val p = screen[(y + sy[k]) * W + (x + sx[k])] and 0xFFFFFF
                        val tp = t.px[sy[k] * t.w + sx[k]]
                        if (!Capture.match(p, tp, tol)) {
                            bad++
                            if (bad > allowed) break
                        }
                        k++
                    }
                    if (bad <= allowed) return Hit(x + t.w / 2, y + t.h / 2)
                    x += stepS
                }
                y += stepS
            }
        }
        return null
    }
}
