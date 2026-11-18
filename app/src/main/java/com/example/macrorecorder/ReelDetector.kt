package com.example.macrorecorder

import kotlin.math.abs

/**
 * Finds the Fisch reel bar without needing any colors set up. It works on the long dark strip:
 *  - the strip itself is dark, the player bar (white, red, any color) is the wide brighter block,
 *  - the fish marker is the thin vertical line that differs from whatever it sits on.
 * Tested against real recordings of the white and red reel bars.
 */
object ReelDetector {

    /** Positions are in pixels relative to the region's left edge. fish is -1 when not seen. */
    class Result(val barL: Int, val barR: Int, val fish: Int)

    /** Pixels are ARGB ints, region is w x h. Only the middle rows are used (rows [r0, r1)). */
    fun detect(px: IntArray, w: Int, h: Int): Result? {
        if (w < 60 || h < 4) return null
        val r0 = (h * 0.3f).toInt().coerceAtMost(h - 4)
        val r1 = maxOf(r0 + 4, (h * 0.7f).toInt()).coerceAtMost(h)
        val rows = r1 - r0
        if (rows < 3) return null

        // median color of each column
        val mr = IntArray(w)
        val mg = IntArray(w)
        val mb = IntArray(w)
        val tmp = IntArray(rows)
        for (x in 0 until w) {
            for (c in 0..2) {
                val shift = 16 - 8 * c
                for (y in 0 until rows) tmp[y] = (px[(r0 + y) * w + x] shr shift) and 255
                tmp.sort()
                val m = tmp[rows / 2]
                when (c) { 0 -> mr[x] = m; 1 -> mg[x] = m; else -> mb[x] = m }
            }
        }

        // track color = median of the darkest 20% of columns
        val order = (0 until w).sortedBy { mr[it] + mg[it] + mb[it] }
        val nDark = maxOf(5, w / 5)
        val tr = median(IntArray(nDark) { mr[order[it]] })
        val tg = median(IntArray(nDark) { mg[order[it]] })
        val tb = median(IntArray(nDark) { mb[order[it]] })
        if ((tr + tg + tb) / 3 > 45) return null // the strip must be dark

        val d = IntArray(w) { abs(mr[it] - tr) + abs(mg[it] - tg) + abs(mb[it] - tb) }

        // runs of "not track" columns (gaps up to 4 columns are bridged)
        var bestL = -1
        var bestR = -1
        val runL = ArrayList<Int>()
        val runR = ArrayList<Int>()
        var i = 0
        while (i < w) {
            if (d[i] > 60) {
                var j = i
                var last = i
                while (j < w && (d[j] > 60 || j - last <= 4)) {
                    if (d[j] > 60) last = j
                    j++
                }
                runL.add(i)
                runR.add(last)
                if (bestL < 0 || last - i > bestR - bestL) {
                    bestL = i
                    bestR = last
                }
                i = last + 1
            } else {
                i++
            }
        }
        if (bestL < 0) return null
        val bw = bestR - bestL + 1
        if (bw < w * 0.06f || bw > w * 0.75f) return null

        // bar color, and how solid the bar is
        val br = median(IntArray(bw) { mr[bestL + it] })
        val bg = median(IntArray(bw) { mg[bestL + it] })
        val bb = median(IntArray(bw) { mb[bestL + it] })
        var solidCount = 0
        val cov = FloatArray(bw)
        for (x in 0 until bw) {
            var differ = 0
            for (y in 0 until rows) {
                val p = px[(r0 + y) * w + bestL + x]
                val dd = abs(((p shr 16) and 255) - br) + abs(((p shr 8) and 255) - bg) + abs((p and 255) - bb)
                if (dd < 150) solidCount++
                if (dd > 60) differ++
            }
            cov[x] = differ.toFloat() / rows
        }
        if (solidCount.toFloat() / (bw * rows) < 0.75f) return null

        // the rest of the strip must be plain dark track
        val rest = BooleanArray(w) { it >= 8 && it < w - 8 && (it < bestL - 3 || it > bestR + 3) }
        var restCount = 0
        var restClose = 0
        for (x in 0 until w) {
            if (rest[x]) {
                restCount++
                if (d[x] < 130) restClose++
            }
        }
        if (restCount < w * 0.2f) return null
        if (restClose.toFloat() / restCount < 0.8f) return null

        // fish marker: thin vertical stripe inside the bar that differs from the bar color...
        var fish = -1
        var bestScore = 0f
        var x = 0
        while (x < bw) {
            if (cov[x] >= 0.45f) {
                var e = x
                var sum = 0f
                while (e + 1 < bw && (cov[e + 1] >= 0.45f || (e + 2 < bw && cov[e + 2] >= 0.45f))) {
                    e++
                }
                var cnt = 0
                for (k in x..e) if (cov[k] >= 0.45f) { sum += cov[k]; cnt++ }
                val len = e - x + 1
                if (len in 5..40 && sum > bestScore) {
                    bestScore = sum
                    fish = bestL + (x + e) / 2
                }
                x = e + 1
            } else {
                x++
            }
        }
        // ...or, when the fish is outside the bar, a faint thin stripe on the dark track
        if (fish < 0) {
            var bestSum = 0
            var s = 0
            while (s < w) {
                val ok = rest[s] && d[s] > 35 && (s < bestL - 6 || s > bestR + 6)
                if (ok) {
                    var e = s
                    var sum = 0
                    var cnt = 0
                    while (e < w && e - s < 80) {
                        val okE = rest[e] && d[e] > 35 && (e < bestL - 6 || e > bestR + 6)
                        if (okE) { sum += d[e]; cnt++ } else if (e + 2 < w && !(rest[e + 1] && d[e + 1] > 35) && !(rest[e + 2] && d[e + 2] > 35)) break
                        e++
                    }
                    val len = e - s
                    if (cnt in 5..40 && sum > bestSum) {
                        bestSum = sum
                        fish = (s + e - 1) / 2
                    }
                    s = e + 1
                } else {
                    s++
                }
            }
        }
        return Result(bestL, bestR, fish)
    }

    private fun median(a: IntArray): Int {
        a.sort()
        return a[a.size / 2]
    }
}
