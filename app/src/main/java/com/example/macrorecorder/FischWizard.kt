package com.example.macrorecorder

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Guided setup for fishing games (made for Roblox Fisch). It freezes a screenshot and asks you to
 * point at a few things, then writes a ready-to-run script. Two rounds:
 *   1/2  while the SHAKE button is on screen: cast point + shake button picture
 *   2/2  while the reel bar is on screen:     one box around the dark reel strip (no colors needed)
 */
class FischWizard(private val svc: MacroService) {

    private class StepDef(val text: String, val box: Boolean, val skippable: Boolean)

    private val roundA = listOf(
        StepDef("1/3  Tap where you press and hold to cast\n(any empty spot of the game view)", false, false),
        StepDef("2/3  Drag a box around the SHAKE button\n(or Skip if your rod has no shake)", true, true)
    )
    private val roundB = listOf(
        StepDef("3/3  Drag a THIN box around the long dark reel strip\n(just the strip, from its left end to its right end)", true, false)
    )

    private var aDone = false
    private var bDone = false

    private var castX = 0
    private var castY = 0
    private var shakeName: String? = null
    private var reel = IntArray(4)

    private var view: SnapView? = null
    private var shot: Bitmap? = null
    private var round = 0
    private var sub = 0

    /** Text for the panel button. */
    fun label(): String = if (!aDone) "🐟 1/2" else if (!bDone) "🐟 2/2" else "🐟 Setup"

    /** Called by the panel button. */
    fun press() {
        if (view != null) return
        if (Capture.bitmap == null) {
            svc.toast("Start screen capture in the app first")
            return
        }
        if (aDone && bDone) {
            aDone = false
            bDone = false
        }
        openRound(if (!aDone) 0 else 1)
    }

    /** Closes the picture without losing finished rounds. */
    fun dismiss() {
        view?.let { svc.removeOverlay(it) }
        view = null
        shot = null
        svc.refreshPanel()
    }

    private fun openRound(r: Int) {
        val b = synchronized(Capture.lock) {
            val src = Capture.bitmap ?: return
            val w = minOf(Capture.width, src.width)
            val h = minOf(Capture.height, src.height)
            if (w <= 0 || h <= 0) return
            Bitmap.createBitmap(src, 0, 0, w, h) // a copy, so the picture stays frozen
        }
        round = r
        sub = 0
        shot = b
        val v = SnapView(svc, b)
        v.listener = object : SnapView.Listener {
            override fun onTap(x: Int, y: Int) = handleTap(x, y)
            override fun onBox(x1: Int, y1: Int, x2: Int, y2: Int) = handleBox(x1, y1, x2, y2)
            override fun onButton(index: Int) {
                when (index) {
                    0 -> skip()
                    1 -> {
                        dismiss()
                        svc.toast("Okay. Get the game ready, then tap the 🐟 button again")
                    }
                    2 -> {
                        aDone = false
                        bDone = false
                        dismiss()
                        svc.toast("Setup cancelled")
                    }
                }
            }
        }
        view = v
        show()
        svc.addFullOverlay(v)
    }

    private fun steps(): List<StepDef> = if (round == 0) roundA else roundB

    private fun show() {
        val v = view ?: return
        val s = steps()[sub]
        v.title = s.text
        v.boxMode = s.box
        v.canSkip = s.skippable
        v.invalidate()
    }

    private fun advance() {
        sub++
        if (sub >= steps().size) finishRound() else show()
    }

    private fun handleTap(x: Int, y: Int) {
        val v = view ?: return
        if (round == 0 && sub == 0) {
            castX = x
            castY = y
            v.marks.add(SnapView.Mark(x, y, v.sample(x, y)))
            advance()
        }
    }

    private fun handleBox(x1: Int, y1: Int, x2: Int, y2: Int) {
        val v = view ?: return
        val b = shot ?: return
        if (Math.abs(x2 - x1) < 8 || Math.abs(y2 - y1) < 8) {
            svc.toast("Drag a bigger box")
            return
        }
        if (round == 0 && sub == 1) {
            val name = Images.saveFrom(svc, b, x1, y1, x2, y2, "shake")
            if (name == null) {
                svc.toast("Couldn't save that box, try again")
                return
            }
            shakeName = name
            v.boxes.add(Rect(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2)))
            advance()
        } else if (round == 1 && sub == 0) {
            reel = intArrayOf(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
            v.boxes.add(Rect(reel[0], reel[1], reel[2], reel[3]))
            advance()
        }
    }

    private fun skip() {
        if (round == 0 && sub == 1) {
            shakeName = null
            advance()
        }
    }

    private fun finishRound() {
        if (round == 0) aDone = true else bDone = true
        dismiss()
        if (aDone && bDone) {
            MacroStore.setScript(svc, buildScript())
            svc.toast("Fisch script saved. Cast once, then tap ▶ Play")
        } else if (aDone) {
            svc.toast("Round 1 done. Now catch a fish until the reel bar shows, then tap 🐟 2/2")
        } else {
            svc.toast("Round 2 done. Now do round 1 (🐟 1/2) while the SHAKE button is visible")
        }
    }

    private fun hex(c: Int) = "%06X".format(c and 0xFFFFFF)

    fun buildScript(): String = buildString {
        val rx1 = reel[0]; val ry1 = reel[1]; val rx2 = reel[2]; val ry2 = reel[3]
        append("# Fisch auto-fish (made by the setup wizard)\n")
        append("# Tune the cast time and reel sensitivity under Rod settings in the app.\n")
        append("loop 0\n")
        append("  # cast: holds for the active rod's cast time (Rod settings in the app)\n")
        append("  cast $castX $castY\n")
        append("  wait 600\n")
        append("  # shake: taps the SHAKE button if the rod has one (rods with 100% lure speed have none)\n")
        append("  untilreel $rx1 $ry1 $rx2 $ry2 30000\n")
        append("    shake 300 30\n")
        append("  end\n")
        append("  # reel: no colors needed, works with the white and red bars (add 'invert' at the end if it goes the wrong way)\n")
        append("  reel $rx1 $ry1 $rx2 $ry2 $castX $castY 60000\n")
        append("  # let the catch screen finish\n")
        append("  wait 1500\n")
        append("end\n")
    }
}
