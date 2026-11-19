package com.example.macrorecorder

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * Shows a frozen screenshot full screen so you can point at things in it while the game keeps
 * running (or is paused) underneath. Reports taps and dragged boxes in screen pixels.
 */
class SnapView(context: Context, private val shot: Bitmap) : View(context) {

    interface Listener {
        fun onTap(x: Int, y: Int)
        fun onBox(x1: Int, y1: Int, x2: Int, y2: Int)
        /** 0 = Skip, 1 = Later, 2 = Cancel */
        fun onButton(index: Int)
    }

    class Mark(val x: Int, val y: Int, val rgb: Int)

    var listener: Listener? = null
    var title: String = ""
    var boxMode = false
    var canSkip = false
    val marks = ArrayList<Mark>()
    val boxes = ArrayList<Rect>()

    private var dragging = false
    private var sx = 0f
    private var sy = 0f
    private var cx = 0f
    private var cy = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val src = Rect(0, 0, shot.width, shot.height)
    private val labels = arrayOf("Skip", "Later", "Cancel")

    private fun ts(): Float = width / 26f
    private fun headerHeight(): Float = ts() * 5.2f

    private fun btnRect(i: Int): RectF {
        val w = width / 3f
        val t = ts()
        return RectF(i * w + t * 0.3f, t * 3.3f, (i + 1) * w - t * 0.3f, t * 4.9f)
    }

    private fun bx(x: Float): Int = (x * shot.width / width.coerceAtLeast(1)).toInt().coerceIn(0, shot.width - 1)
    private fun by(y: Float): Int = (y * shot.height / height.coerceAtLeast(1)).toInt().coerceIn(0, shot.height - 1)

    /** Average color of the 3x3 pixels around a spot (0xRRGGBB). */
    fun sample(x: Int, y: Int): Int {
        var r = 0
        var g = 0
        var b = 0
        var n = 0
        for (dy in -1..1) {
            for (dx in -1..1) {
                val px = x + dx
                val py = y + dy
                if (px < 0 || py < 0 || px >= shot.width || py >= shot.height) continue
                val p = shot.getPixel(px, py)
                r += (p shr 16) and 255
                g += (p shr 8) and 255
                b += p and 255
                n++
            }
        }
        if (n == 0) return 0
        return ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
    }

    override fun onDraw(c: Canvas) {
        c.drawBitmap(shot, src, Rect(0, 0, width, height), null)
        val kx = width.toFloat() / shot.width
        val ky = height.toFloat() / shot.height

        paint.textAlign = Paint.Align.LEFT
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = Color.GREEN
        for (b in boxes) c.drawRect(b.left * kx, b.top * ky, b.right * kx, b.bottom * ky, paint)

        for (m in marks) {
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb((m.rgb shr 16) and 255, (m.rgb shr 8) and 255, m.rgb and 255)
            c.drawCircle(m.x * kx, m.y * ky, 20f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            paint.color = Color.WHITE
            c.drawCircle(m.x * kx, m.y * ky, 20f, paint)
        }

        if (dragging && boxMode) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            paint.color = Color.YELLOW
            c.drawRect(minOf(sx, cx), minOf(sy, cy), maxOf(sx, cx), maxOf(sy, cy), paint)
        }

        // header with instructions and buttons
        val t = ts()
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(225, 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), headerHeight(), paint)
        paint.color = Color.WHITE
        paint.textSize = t
        var y = t * 1.4f
        for (line in title.split("\n")) {
            c.drawText(line, t * 0.5f, y, paint)
            y += t * 1.2f
        }
        paint.textAlign = Paint.Align.CENTER
        for (i in 0..2) {
            if (i == 0 && !canSkip) continue
            val r = btnRect(i)
            paint.color = Color.rgb(70, 70, 70)
            c.drawRoundRect(r, 16f, 16f, paint)
            paint.color = Color.WHITE
            c.drawText(labels[i], r.centerX(), r.centerY() + t * 0.35f, paint)
        }
        paint.textAlign = Paint.Align.LEFT
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.y < headerHeight()) {
                    for (i in 0..2) {
                        if (i == 0 && !canSkip) continue
                        val r = btnRect(i)
                        if (r.contains(e.x, e.y)) {
                            listener?.onButton(i)
                            break
                        }
                    }
                    dragging = false
                } else {
                    dragging = true
                    sx = e.x; sy = e.y; cx = e.x; cy = e.y
                    invalidate()
                }
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                cx = e.x; cy = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP -> if (dragging) {
                dragging = false
                val x1 = bx(sx); val y1 = by(sy)
                val x2 = bx(e.x); val y2 = by(e.y)
                if (boxMode) {
                    listener?.onBox(x1, y1, x2, y2)
                } else {
                    listener?.onTap(x2, y2)
                }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                invalidate()
            }
        }
        return true
    }
}
