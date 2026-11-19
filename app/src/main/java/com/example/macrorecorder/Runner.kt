package com.example.macrorecorder

import android.os.SystemClock
import kotlin.math.max

/** Runs parsed script commands. Always called on a background thread. */
class ScriptRunner(private val svc: MacroService, private val speed: Float) {

    @Volatile var stopped = false

    fun run(cmds: List<Cmd>) {
        for (c in cmds) {
            if (stopped) return
            exec(c)
        }
    }

    /** Sleep that ends early when stopped. */
    fun pause(ms: Long) {
        var left = ms
        while (left > 0 && !stopped) {
            val s = minOf(left, 50L)
            try {
                Thread.sleep(s)
            } catch (e: InterruptedException) {
                stopped = true
                return
            }
            left -= s
        }
    }

    private fun needCapture() {
        if (Capture.bitmap == null) {
            throw RunError("Screen capture is off. Open the app and tap 'Start screen capture'.")
        }
    }

    private fun exec(c: Cmd) {
        when (c) {
            is Cmd.Tap -> svc.gestureBlocking(c.x, c.y, c.x, c.y, 50)
            is Cmd.Cast -> {
                Capture.status = "Casting"
                svc.gestureBlocking(c.x, c.y, c.x, c.y, MacroStore.activeRod(svc).holdMs.toLong())
            }
            is Cmd.Hold -> {
                Capture.status = "Holding"
                svc.gestureBlocking(c.x, c.y, c.x, c.y, c.ms)
            }
            is Cmd.Swipe -> svc.gestureBlocking(c.x1, c.y1, c.x2, c.y2, c.ms)
            is Cmd.Wait -> pause((c.ms / speed.coerceIn(0.1f, 10f)).toLong())

            is Cmd.WaitColor -> {
                needCapture()
                val end = SystemClock.uptimeMillis() + c.timeout
                while (!stopped && SystemClock.uptimeMillis() < end) {
                    val p = Capture.pixel(c.x, c.y)
                    if (p != null && Capture.match(p, c.rgb, c.tol)) break
                    pause(50)
                }
            }

            is Cmd.FindTap -> {
                needCapture()
                val end = SystemClock.uptimeMillis() + c.timeout
                while (!stopped && SystemClock.uptimeMillis() < end) {
                    val hit = Capture.scan(c.rgb, c.tol)
                    if (hit != null) {
                        svc.gestureBlocking(hit.x, hit.y, hit.x, hit.y, 50)
                        break
                    }
                    pause(100)
                }
            }

            is Cmd.IfColor -> {
                needCapture()
                val p = Capture.pixel(c.x, c.y)
                if (p != null && Capture.match(p, c.rgb, c.tol)) run(c.body)
            }

            is Cmd.Loop -> {
                var i = 0
                while (!stopped && (c.count == 0 || i < c.count)) {
                    run(c.body)
                    i++
                    pause(20) // keeps empty or very fast loops from spinning
                }
            }

            is Cmd.FindImg -> {
                needCapture()
                Capture.status = "Looking for button"
                val tpl = Images.load(svc, c.name)
                    ?: throw RunError("The picture '${c.name}' isn't saved yet. Run the 🐟 setup (round 1/2 saves the SHAKE button as 'shake') or use the Crop button.")
                val end = SystemClock.uptimeMillis() + c.timeout
                while (!stopped && SystemClock.uptimeMillis() < end) {
                    val hit = Images.find(tpl, c.tol)
                    if (hit != null) {
                        svc.gestureBlocking(hit.x, hit.y, hit.x, hit.y, 50)
                        break
                    }
                    pause(150)
                }
            }

            is Cmd.Shake -> {
                if (!MacroStore.activeRod(svc).shake) {
                    pause(150) // this rod bites with no shake step
                } else {
                    needCapture()
                    val tpl = Images.load(svc, "shake")
                    if (tpl == null) {
                        Capture.status = "No SHAKE picture saved (run 🐟 setup, or turn shake off for this rod)"
                        pause(150)
                    } else {
                        Capture.status = "Looking for shake"
                        val end = SystemClock.uptimeMillis() + c.timeout
                        while (!stopped && SystemClock.uptimeMillis() < end) {
                            val hit = Images.find(tpl, c.tol)
                            if (hit != null) {
                                svc.gestureBlocking(hit.x, hit.y, hit.x, hit.y, 50)
                                break
                            }
                            pause(150)
                        }
                    }
                }
            }

            is Cmd.WaitGone -> {
                needCapture()
                val tpl = Images.load(svc, c.name)
                    ?: throw RunError("The picture '${c.name}' isn't saved yet. Run the 🐟 setup (round 1/2 saves the SHAKE button as 'shake') or use the Crop button.")
                val end = SystemClock.uptimeMillis() + c.timeout
                while (!stopped && SystemClock.uptimeMillis() < end) {
                    if (Images.find(tpl, c.tol) == null) break
                    pause(150)
                }
            }

            is Cmd.IfImg -> {
                needCapture()
                val tpl = Images.load(svc, c.name)
                    ?: throw RunError("The picture '${c.name}' isn't saved yet. Run the 🐟 setup (round 1/2 saves the SHAKE button as 'shake') or use the Crop button.")
                if (Images.find(tpl, c.tol) != null) run(c.body)
            }

            is Cmd.WaitImg -> {
                needCapture()
                val tpl = Images.load(svc, c.name)
                    ?: throw RunError("The picture '${c.name}' isn't saved yet. Run the 🐟 setup (round 1/2 saves the SHAKE button as 'shake') or use the Crop button.")
                val end = SystemClock.uptimeMillis() + c.timeout
                while (!stopped && SystemClock.uptimeMillis() < end) {
                    if (Images.find(tpl, c.tol) != null) break
                    pause(150)
                }
            }

            is Cmd.Until -> {
                needCapture()
                Capture.status = "Waiting for bite"
                Capture.dbgBox = intArrayOf(minOf(c.x1, c.x2), minOf(c.y1, c.y2), maxOf(c.x1, c.x2), maxOf(c.y1, c.y2))
                try {
                    val end = SystemClock.uptimeMillis() + c.timeout
                    while (!stopped && SystemClock.uptimeMillis() < end) {
                        if (Capture.scan(c.rgb, c.tol, c.x1, c.y1, c.x2, c.y2) != null) break
                        run(c.body)
                        pause(20)
                    }
                } finally {
                    Capture.dbgBox = null
                }
            }

            is Cmd.UntilReel -> {
                needCapture()
                Capture.status = "Waiting for bite"
                Capture.dbgBox = intArrayOf(minOf(c.x1, c.x2), minOf(c.y1, c.y2), maxOf(c.x1, c.x2), maxOf(c.y1, c.y2))
                try {
                    val end = SystemClock.uptimeMillis() + c.timeout
                    while (!stopped && SystemClock.uptimeMillis() < end) {
                        if (reelNow(c.x1, c.y1, c.x2, c.y2) != null) break
                        run(c.body)
                        pause(20)
                    }
                } finally {
                    Capture.dbgBox = null
                }
            }

            is Cmd.Reel -> {
                needCapture()
                Capture.status = "Reeling"
                Capture.dbgBox = intArrayOf(minOf(c.x1, c.x2), minOf(c.y1, c.y2), maxOf(c.x1, c.x2), maxOf(c.y1, c.y2))
                val engaged = try {
                    reel(c)
                } finally {
                    Capture.dbgBox = null
                    Capture.dbgFish = -1
                    Capture.dbgBar = -1
                }
                if (engaged) Capture.reels = Capture.reels + 1
                Capture.status = "Caught, resetting"
            }

            is Cmd.Track -> {
                needCapture()
                Capture.status = "Reeling"
                Capture.dbgBox = intArrayOf(minOf(c.x1, c.x2), minOf(c.y1, c.y2), maxOf(c.x1, c.x2), maxOf(c.y1, c.y2))
                val engaged = try {
                    track(c)
                } finally {
                    Capture.dbgBox = null
                    Capture.dbgFish = -1
                    Capture.dbgBar = -1
                }
                if (engaged) Capture.reels = Capture.reels + 1
                Capture.status = "Caught, resetting"
            }
        }
    }

    private class ReelNow(val barL: Int, val barR: Int, val fish: Int)

    /** Looks at the screen right now. Positions come back as screen pixels (fish = -1 if unseen). */
    private fun reelNow(x1: Int, y1: Int, x2: Int, y2: Int): ReelNow? {
        val g = Capture.grab(x1, y1, x2, y2) ?: return null
        val r = ReelDetector.detect(g.px, g.w, g.h) ?: return null
        return ReelNow(g.ox + r.barL, g.ox + r.barR, if (r.fish < 0) -1 else g.ox + r.fish)
    }

    /**
     * Color-free reeling. Holding the screen moves the bar right, letting go moves it left, so:
     * fish to the right of the bar -> press, otherwise release. It looks a little ahead using
     * the measured speeds, and presses longer the further off the bar is.
     * Ends at the time limit, or after the reel strip has been gone for 1.5 seconds.
     */
    private fun reel(c: Cmd.Reel): Boolean {
        var engaged = false
        val start = SystemClock.uptimeMillis()
        val end = start + c.ms
        var lastSeen = start
        val sign = if (c.invert) -1f else 1f
        val lead = 140f
        val sens = MacroStore.activeRod(svc).sens.coerceIn(0.5f, 2f)

        var lastFish = Float.NaN
        var lastFishTime = 0L
        var prevFish = Float.NaN
        var prevBar = Float.NaN
        var prevTime = 0L
        var fishSpeed = 0f
        var barSpeed = 0f

        while (!stopped && SystemClock.uptimeMillis() < end) {
            val r = reelNow(c.x1, c.y1, c.x2, c.y2)
            val now = SystemClock.uptimeMillis()
            if (r == null) {
                if (now - lastSeen > 1500) return engaged
                Capture.dbgFish = -1
                Capture.dbgBar = -1
                prevFish = Float.NaN
                pause(30)
                continue
            }
            lastSeen = now
            engaged = true

            val barW = (r.barR - r.barL).toFloat()
            val bx = (r.barL + r.barR) / 2f
            Capture.dbgBar = bx.toInt()
            if (r.fish >= 0) {
                lastFish = r.fish.toFloat()
                lastFishTime = now
            }
            // use the last seen fish position for up to 400 ms if it is briefly hidden
            val fx = if (!lastFish.isNaN() && now - lastFishTime < 400) lastFish else Float.NaN
            Capture.dbgFish = if (fx.isNaN()) -1 else fx.toInt()
            if (fx.isNaN()) {
                pause(30)
                continue
            }

            if (!prevFish.isNaN() && now > prevTime) {
                val dt = (now - prevTime).toFloat()
                fishSpeed = fishSpeed * 0.6f + 0.4f * ((fx - prevFish) / dt)
                barSpeed = barSpeed * 0.6f + 0.4f * ((bx - prevBar) / dt)
            }
            prevFish = fx
            prevBar = bx
            prevTime = now

            val err = sign * ((fx + fishSpeed * lead) - (bx + barSpeed * lead))
            val dead = maxOf(8f, barW * 0.08f / sens)
            if (err > dead) {
                val dur = (40f + err * 0.25f * sens).toLong().coerceIn(40L, 220L)
                svc.gestureBlocking(c.px, c.py, c.px, c.py, dur)
            } else {
                pause(20)
            }
        }
        return engaged
    }

    /**
     * Keeps the bar under the fish. Finds both colors in the region, estimates how fast each one
     * is moving, and presses the press point for a length that grows with how far off the bar is
     * (looking a little ahead so it does not overshoot). Releasing lets the bar drift back.
     * Ends at the time limit, or after both colors have been missing for 1.5 seconds.
     */
    private fun track(c: Cmd.Track): Boolean {
        needCapture()
        var engaged = false
        val start = SystemClock.uptimeMillis()
        val end = start + c.ms
        var lastSeen = start
        val dead = max(4, kotlin.math.abs(c.x2 - c.x1) / 50)
        val sign = if (c.invert) -1f else 1f
        val lead = 140f // milliseconds to look ahead

        var prevFish = Float.NaN
        var prevBar = Float.NaN
        var prevTime = 0L
        var fishSpeed = 0f // pixels per millisecond, smoothed
        var barSpeed = 0f

        while (!stopped && SystemClock.uptimeMillis() < end) {
            val fish = Capture.scan(c.fish, c.tol, c.x1, c.y1, c.x2, c.y2)
            val bar = Capture.scan(c.bar, c.tol, c.x1, c.y1, c.x2, c.y2)
            val now = SystemClock.uptimeMillis()
            if (fish == null || bar == null) {
                if (fish == null && bar == null && now - lastSeen > 1500) return engaged
                Capture.dbgFish = fish?.x ?: -1
                Capture.dbgBar = bar?.x ?: -1
                prevFish = Float.NaN
                pause(30)
                continue
            }
            lastSeen = now
            engaged = true
            Capture.dbgFish = fish.x
            Capture.dbgBar = bar.x

            val fx = fish.x.toFloat()
            val bx = bar.x.toFloat()
            if (!prevFish.isNaN() && now > prevTime) {
                val dt = (now - prevTime).toFloat()
                fishSpeed = fishSpeed * 0.6f + 0.4f * ((fx - prevFish) / dt)
                barSpeed = barSpeed * 0.6f + 0.4f * ((bx - prevBar) / dt)
            }
            prevFish = fx
            prevBar = bx
            prevTime = now

            // Positive = the bar needs to move toward the press direction.
            val err = sign * ((fx + fishSpeed * lead) - (bx + barSpeed * lead))
            if (err > dead) {
                val dur = (40f + err * 0.25f).toLong().coerceIn(40L, 200L)
                svc.gestureBlocking(c.px, c.py, c.px, c.py, dur)
            } else {
                pause(20)
            }
        }
        return engaged
    }
}
