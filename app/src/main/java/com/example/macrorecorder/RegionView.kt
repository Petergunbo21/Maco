package com.example.macrorecorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * A rectangle you place on top of the live game: drag it to move it, drag the small triangle in its
 * corner to resize it, then tap Save. Coordinates are screen pixels (the view fills the screen).
 */
class RegionView(context: Context, start: Rect) : View(context) {

    interface Listener {
        fun onSave(r: Rect)
        fun onCancel()
    }

    var listener: Listener? = null
    var title: String = ""

    private val rect = RectF(start)
    private var mode = 0 // 0 nothing, 1 move, 2 resize
    private var lastX = 0f
    private var lastY = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val labels = arrayOf("Save", "Cancel")

    private fun ts(): Float = width / 26f
    private fun headerHeight(): Float = ts() * 5.2f

    private fun btnRect(i: Int): RectF {
        val w = width / 2f
        val t = ts()
        return RectF(i * w + t * 0.3f, t * 3.3f, (i + 1) * w - t * 0.3f, t * 4.9f)
    }

    private fun handle(): Float = 64f

    override fun onDraw(c: Canvas) {
        val teal = Color.rgb(0, 220, 200)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(45, 0, 220, 200)
        c.drawRect(rect, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5f
        paint.color = teal
        c.drawRect(rect, paint)

        // resize triangle in the bottom-right corner
        paint.style = Paint.Style.FILL
        path.reset()
        path.moveTo(rect.right, rect.bottom - handle())
        path.lineTo(rect.right, rect.bottom)
        path.lineTo(rect.right - handle(), rect.bottom)
        path.close()
        c.drawPath(path, paint)

        // header
        val t = ts()
        paint.color = Color.argb(225, 0, 0, 0)
        c.drawRect(0f, 0f, width.toFloat(), headerHeight(), paint)
        paint.color = Color.WHITE
        paint.textSize = t
        paint.textAlign = Paint.Align.LEFT
        var y = t * 1.4f
        for (line in title.split("\n")) {
            c.drawText(line, t * 0.5f, y, paint)
            y += t * 1.2f
        }
        paint.textAlign = Paint.Align.CENTER
        for (i in 0..1) {
            val r = btnRect(i)
            paint.color = if (i == 0) Color.rgb(0, 120, 110) else Color.rgb(70, 70, 70)
            c.drawRoundRect(r, 16f, 16f, paint)
            paint.color = Color.WHITE
            c.drawText(labels[i], r.centerX(), r.centerY() + t * 0.35f, paint)
        }
        paint.textAlign = Paint.Align.LEFT
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = 0
                if (e.y < headerHeight()) {
                    for (i in 0..1) {
                        if (btnRect(i).contains(e.x, e.y)) {
                            if (i == 0) listener?.onSave(clamped()) else listener?.onCancel()
                            return true
                        }
                    }
                } else if (e.x >= rect.right - handle() * 1.6f && e.x <= rect.right + handle() &&
                    e.y >= rect.bottom - handle() * 1.6f && e.y <= rect.bottom + handle()
                ) {
                    mode = 2
                } else if (rect.contains(e.x, e.y)) {
                    mode = 1
                }
                lastX = e.x
                lastY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - lastX
                val dy = e.y - lastY
                if (mode == 1) {
                    rect.offset(dx, dy)
                } else if (mode == 2) {
                    rect.right = maxOf(rect.left + 40f, rect.right + dx)
                    rect.bottom = maxOf(rect.top + 12f, rect.bottom + dy)
                }
                lastX = e.x
                lastY = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mode = 0
        }
        return true
    }

    private fun clamped(): Rect {
        val l = rect.left.toInt().coerceAtLeast(0)
        val t = rect.top.toInt().coerceAtLeast(0)
        val r = rect.right.toInt().coerceAtMost(width.coerceAtLeast(l + 1))
        val b = rect.bottom.toInt().coerceAtMost(height.coerceAtLeast(t + 1))
        return Rect(l, t, r, b)
    }
}
