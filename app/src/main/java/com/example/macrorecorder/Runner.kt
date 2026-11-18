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
            is Cmd.Hold -> svc.gestureBlocking(c.x, c.y, c.x, c.y, c.ms)
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

            is Cmd.Track -> track(c)
        }
    }

    /**
     * Keeps the bar under the fish: finds both colors in the region, and taps the press
     * point (short presses) whenever the bar is on the wrong side of the fish.
     * Ends at the time limit, or after both colors have been missing for 1.5 seconds.
     */
    private fun track(c: Cmd.Track) {
        needCapture()
        val end = SystemClock.uptimeMillis() + c.ms
        var lastSeen = SystemClock.uptimeMillis()
        val dead = max(4, kotlin.math.abs(c.x2 - c.x1) / 50)

        while (!stopped && SystemClock.uptimeMillis() < end) {
            val fish = Capture.scan(c.fish, c.tol, c.x1, c.y1, c.x2, c.y2)
            val bar = Capture.scan(c.bar, c.tol, c.x1, c.y1, c.x2, c.y2)
            val now = SystemClock.uptimeMillis()
            if (fish == null || bar == null) {
                if (fish == null && bar == null && now - lastSeen > 1500) return
                pause(30)
                continue
            }
            lastSeen = now
            val press = if (!c.invert) bar.x < fish.x - dead else bar.x > fish.x + dead
            if (press) svc.gestureBlocking(c.px, c.py, c.px, c.py, 60) else pause(25)
        }
    }
}
