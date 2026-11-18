package com.example.macrorecorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/**
 * Live "what the macro sees" overlay: an outline around the reel area, a green arrow above it at the
 * fish position and a red arrow below it at the bar position. Everything is drawn OUTSIDE the box,
 * so it never disturbs the color scan inside it. Not touchable.
 */
class ReelOverlay(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val b = Capture.dbgBox ?: return
        val gap = 6f
        val left = b[0].toFloat()
        val top = b[1].toFloat()
        val right = b[2].toFloat()
        val bottom = b[3].toFloat()

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.argb(170, 255, 255, 255)
        c.drawRect(left - gap, top - gap, right + gap, bottom + gap, paint)

        paint.style = Paint.Style.FILL
        val f = Capture.dbgFish
        if (f >= 0) {
            paint.color = Color.rgb(0, 230, 90)
            path.reset()
            path.moveTo(f - 14f, top - gap - 24f)
            path.lineTo(f + 14f, top - gap - 24f)
            path.lineTo(f.toFloat(), top - gap - 4f)
            path.close()
            c.drawPath(path, paint)
        }
        val br = Capture.dbgBar
        if (br >= 0) {
            paint.color = Color.rgb(255, 60, 60)
            path.reset()
            path.moveTo(br - 14f, bottom + gap + 24f)
            path.lineTo(br + 14f, bottom + gap + 24f)
            path.lineTo(br.toFloat(), bottom + gap + 4f)
            path.close()
            c.drawPath(path, paint)
        }
    }
}
