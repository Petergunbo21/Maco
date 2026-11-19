package com.example.macrorecorder

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.example.macrorecorder.MacroStore.loops
import com.example.macrorecorder.MacroStore.speed
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.hypot

/**
 * Accessibility service that:
 *  - shows a floating panel (Rec / Play / Stop / Pick / Close) on top of any app
 *  - records taps and swipes through a transparent capture layer and writes them as a script
 *  - runs scripts (taps, holds, swipes, waits, color checks, reel tracking)
 *  - picks a screen position + color for use in scripts
 */
class MacroService : AccessibilityService() {

    companion object {
        var instance: MacroService? = null
    }

    private data class Step(
        val delay: Long, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val dur: Long
    )

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    private var panel: View? = null
    private var layer: FrameLayout? = null
    private var layerParams: WindowManager.LayoutParams? = null
    private var recButton: TextView? = null
    private var playButton: TextView? = null
    private var fischButton: TextView? = null
    private var statusView: TextView? = null
    private var reelOverlay: ReelOverlay? = null
    private var regionView: RegionView? = null
    private val hiddenWhileRunning = mutableListOf<View>()
    private var wizard: FischWizard? = null

    private val steps = mutableListOf<Step>()
    private var recording = false
    private var picking = false
    private var lastEventEnd = 0L
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    private var worker: Thread? = null
    @Volatile private var isRunning = false
    private var runner: ScriptRunner? = null

    // ---------- lifecycle ----------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showPanel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        stopAll()
        hidePanel()
        instance = null
        super.onDestroy()
    }

    fun toast(msg: String) {
        handler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    // ---------- floating panel ----------

    fun showPanel() {
        if (panel != null) return
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.argb(220, 30, 30, 30))
            setPadding(6, 6, 6, 6)
        }
        // Two rows, so the panel never gets wider than the window allows.
        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(row1)
        bar.addView(row2)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 200
        }

        fun button(label: String, onClick: () -> Unit) = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(22, 16, 22, 16)
            setSingleLine(true)
            setOnClickListener { onClick() }
        }

        val handle = TextView(this).apply {
            text = "≡"
            setTextColor(Color.LTGRAY)
            textSize = 20f
            setPadding(16, 8, 16, 8)
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = params.x; startY = params.y
                        touchX = e.rawX; touchY = e.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = startX + (e.rawX - touchX).toInt()
                        params.y = startY + (e.rawY - touchY).toInt()
                        wm.updateViewLayout(bar, params)
                    }
                }
                true
            }
        }

        val rec = button("● Rec") { if (recording) stopAll() else startRecording() }
        val play = button("▶ Play") { startPlayback() }
        recButton = rec
        playButton = play
        val w = wizard ?: FischWizard(this).also { wizard = it }
        val fisch = button(w.label()) { startFisch() }
        fischButton = fisch
        val pick = button("◎ Pick") { startPick() }
        val crop = button("▭ Crop") { startCrop() }
        val close = button("✕") { stopAll(); hidePanel() }
        hiddenWhileRunning.clear()
        hiddenWhileRunning.addAll(listOf(rec, fisch, row2))
        row1.addView(handle)
        row1.addView(rec)
        row1.addView(play)
        row1.addView(button("■ Stop") { stopAll() })
        val statusText = TextView(this).apply {
            setTextColor(Color.rgb(160, 255, 160))
            textSize = 12f
            setPadding(16, 16, 16, 16)
            visibility = View.GONE
        }
        statusView = statusText
        row1.addView(statusText)
        row1.addView(fisch)
        row2.addView(button("▶ Job") { startAbstract(null, 0) })
        row2.addView(button("⬚ Box") { editReelBox() })
        row2.addView(pick)
        row2.addView(crop)
        row2.addView(close)

        wm.addView(bar, params)
        panel = bar
    }

    fun hidePanel() {
        panel?.let { runCatching { wm.removeView(it) } }
        panel = null
        recButton = null
        playButton = null
        fischButton = null
        statusView = null
        hiddenWhileRunning.clear()
    }

    private fun updateButtons() {
        handler.post {
            val running = isRunning
            recButton?.text = if (recording) "● Recording" else "● Rec"
            recButton?.setTextColor(if (recording) Color.RED else Color.WHITE)
            playButton?.text = if (running) "▶ Running" else "▶ Play"
            fischButton?.text = wizard?.label() ?: "🐟"
            // While a script runs, keep only Play/Stop so the panel stays small and out of the way.
            for (v in hiddenWhileRunning) v.visibility = if (running) View.GONE else View.VISIBLE
            statusView?.visibility = if (running) View.VISIBLE else View.GONE
            // Re-measure the floating window, otherwise it keeps its old width and wraps the buttons.
            panel?.let { v ->
                v.requestLayout()
                val lp = v.layoutParams as? WindowManager.LayoutParams
                if (lp != null) {
                    lp.width = WindowManager.LayoutParams.WRAP_CONTENT
                    lp.height = WindowManager.LayoutParams.WRAP_CONTENT
                    runCatching { wm.updateViewLayout(v, lp) }
                }
            }
        }
    }

    /** Refreshes the status line and reel overlay while a script runs. */
    private val ticker = object : Runnable {
        override fun run() {
            if (!isRunning) {
                removeReelOverlay()
                return
            }
            statusView?.text = "${Capture.status}  ·  reels ${Capture.reels}"
            reelOverlay?.invalidate()
            handler.postDelayed(this, 80)
        }
    }

    private fun addReelOverlay() {
        if (reelOverlay != null) return
        val v = ReelOverlay(this)
        reelOverlay = v
        addFullOverlay(v, false)
    }

    private fun removeReelOverlay() {
        reelOverlay?.let { removeOverlay(it) }
        reelOverlay = null
    }

    fun refreshPanel() = updateButtons()

    // ---------- helpers for the Fisch setup picture ----------

    fun addFullOverlay(v: View, touchable: Boolean = true) {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (!touchable) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        wm.addView(v, p)
    }

    fun removeOverlay(v: View) {
        // Posted so a view can safely remove itself from inside its own touch handler.
        handler.post { runCatching { wm.removeView(v) } }
    }

    private val boxPattern = Regex("(?m)^(\\s*(?:reel|untilreel)\\s+)(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)")

    /** Drag-and-resize editor for the reel box; saves the new box into the script's reel lines. */
    private fun editReelBox() {
        stopAll()
        val text = MacroStore.script(this)
        val m = boxPattern.find(text)
        val start = if (m != null) {
            Rect(m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt(), m.groupValues[5].toInt())
        } else {
            Rect(740, 880, 1660, 925)
        }
        val v = RegionView(this, start)
        v.title = "Reel box: drag it onto the long dark reel strip,\ndrag the corner triangle to resize, then Save"
        v.listener = object : RegionView.Listener {
            override fun onSave(r: Rect) {
                closeRegion()
                val current = MacroStore.script(this@MacroService)
                if (boxPattern.containsMatchIn(current)) {
                    val updated = boxPattern.replace(current) { it.groupValues[1] + "${r.left} ${r.top} ${r.right} ${r.bottom}" }
                    MacroStore.setScript(this@MacroService, updated)
                    toast("Reel box saved: ${r.left} ${r.top} ${r.right} ${r.bottom}")
                } else {
                    toast("No reel step in your script yet. Run the 🐟 setup first")
                }
            }

            override fun onCancel() {
                closeRegion()
            }
        }
        regionView = v
        addFullOverlay(v)
    }

    private fun closeRegion() {
        regionView?.let { removeOverlay(it) }
        regionView = null
    }

    private fun startFisch() {
        stopAll()
        (wizard ?: FischWizard(this).also { wizard = it }).press()
    }

    // ---------- full-screen touch layer (recording and picking) ----------

    private fun addLayer(color: Int, listener: (MotionEvent) -> Unit) {
        removeLayer()
        val l = FrameLayout(this).apply { setBackgroundColor(color) }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        l.setOnTouchListener { _, e -> listener(e); true }
        wm.addView(l, p)
        layer = l
        layerParams = p

        // Keep the control panel above the touch layer.
        panel?.let { v ->
            val lp = v.layoutParams as WindowManager.LayoutParams
            runCatching { wm.removeView(v); wm.addView(v, lp) }
        }
    }

    private fun removeLayer() {
        layer?.let { runCatching { wm.removeView(it) } }
        layer = null
        layerParams = null
    }

    // ---------- recording ----------

    private fun startRecording() {
        stopAll()
        steps.clear()
        recording = true
        lastEventEnd = 0L
        updateButtons()
        addLayer(Color.argb(30, 255, 0, 0)) { onRecordTouch(it) }
        toast("Recording: tap or swipe in your game")
    }

    private fun onRecordTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                downTime = e.eventTime
            }
            MotionEvent.ACTION_UP -> {
                val dur = (e.eventTime - downTime).coerceAtLeast(1)
                val delay = if (lastEventEnd == 0L) 300L else (downTime - lastEventEnd).coerceAtLeast(0)
                lastEventEnd = e.eventTime
                steps.add(Step(delay, downX, downY, e.rawX, e.rawY, dur))
                passThrough(downX, downY, e.rawX, e.rawY, dur)
            }
        }
    }

    /** Re-sends the recorded gesture to the app below, so the game still sees your input. */
    private fun passThrough(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long) {
        val l = layer ?: return
        val p = layerParams ?: return
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        runCatching { wm.updateViewLayout(l, p) }
        fun restore() {
            if (recording && layer === l) {
                p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                runCatching { wm.updateViewLayout(l, p) }
            }
        }
        val ok = dispatchGesture(buildGesture(x1, y1, x2, y2, dur), object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = restore()
            override fun onCancelled(g: GestureDescription?) = restore()
        }, null)
        if (!ok) restore()
    }

    private fun finishRecording() {
        recording = false
        removeLayer()
        MacroStore.setScript(this, stepsToScript())
        toast("Saved ${steps.size} step(s) as a script")
        updateButtons()
    }

    private fun stepsToScript(): String = buildString {
        append("# Recorded macro\n")
        for (s in steps) {
            if (s.delay > 0) append("wait ${s.delay}\n")
            val x1 = s.x1.toInt(); val y1 = s.y1.toInt()
            val x2 = s.x2.toInt(); val y2 = s.y2.toInt()
            when {
                hypot(s.x2 - s.x1, s.y2 - s.y1) > 10f -> append("swipe $x1 $y1 $x2 $y2 ${s.dur}\n")
                s.dur < 250 -> append("tap $x1 $y1\n")
                else -> append("hold $x1 $y1 ${s.dur}\n")
            }
        }
    }

    // ---------- pick position + color ----------

    private fun startPick() {
        stopAll()
        if (Capture.bitmap == null) {
            toast("Start screen capture in the app first (needed to read colors)")
            return
        }
        picking = true
        addLayer(Color.argb(1, 0, 0, 0)) { e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                val x = e.rawX.toInt()
                val y = e.rawY.toInt()
                val c = Capture.pixel(x, y)
                picking = false
                handler.post { removeLayer() }
                if (c == null) {
                    toast("Couldn't read that spot")
                } else {
                    val text = "%d %d %06X".format(x, y, c)
                    val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("pick", text))
                    toast("x y color: $text (copied)")
                }
            }
        }
        toast("Tap the spot you want to read")
    }

    // ---------- crop a picture for findimg ----------

    private fun startCrop() {
        stopAll()
        if (Capture.bitmap == null) {
            toast("Start screen capture in the app first")
            return
        }
        var sx = 0f
        var sy = 0f
        addLayer(Color.argb(1, 0, 0, 0)) { e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY
                }
                MotionEvent.ACTION_UP -> {
                    val x1 = sx.toInt(); val y1 = sy.toInt()
                    val x2 = e.rawX.toInt(); val y2 = e.rawY.toInt()
                    handler.post { removeLayer() }
                    val name = Images.saveCrop(this, x1, y1, x2, y2)
                    if (name == null) {
                        toast("Drag a box at least 8px wide and tall")
                    } else {
                        val text = "findimg $name 10000 30"
                        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("findimg", text))
                        toast("Saved. Copied: $text")
                    }
                }
            }
        }
        toast("Drag a box around the picture to save it")
    }

    // ---------- playback ----------

    private fun startPlayback() {
        if (recording) finishRecording()
        if (worker?.isAlive == true) return
        stopAll()

        val scriptText = MacroStore.script(this)
        if (MacroStore.isPlaceholder(scriptText)) {
            // Nothing set up yet: lead the person into the 🐟 setup instead of running a dummy script.
            toast("Not set up yet. Doing the 🐟 setup first (2 quick rounds)")
            startFisch()
            return
        }

        val cmds = try {
            ScriptParser.parse(scriptText)
        } catch (e: ScriptError) {
            toast(e.message ?: "Script error")
            return
        }
        if (cmds.isEmpty()) {
            toast("Script is empty. Record something or write a script in the app")
            return
        }

        val total = loops
        val r = ScriptRunner(this, speed)
        runner = r
        toast(if (total == 0) "Playing (repeats until Stop)" else "Playing $total time(s)")

        isRunning = true
        Capture.reels = 0
        Capture.status = "Starting"
        if (MacroStore.overlayOn(this)) addReelOverlay()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        worker = Thread {
            try {
                var i = 0
                while (!r.stopped && (total == 0 || i < total)) {
                    r.run(cmds)
                    i++
                    if (!r.stopped) r.pause(200)
                }
                if (!r.stopped) toast("Macro finished")
            } catch (e: RunError) {
                toast(e.message ?: "Script stopped")
            } finally {
                isRunning = false
                updateButtons()
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
        updateButtons()
    }

    /** Performs a gesture and waits until it finishes. Called from the script thread. */
    fun gestureBlocking(x1: Int, y1: Int, x2: Int, y2: Int, dur: Long) {
        val latch = CountDownLatch(1)
        handler.post {
            val ok = dispatchGesture(
                buildGesture(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), dur),
                object : GestureResultCallback() {
                    override fun onCompleted(g: GestureDescription?) = latch.countDown()
                    override fun onCancelled(g: GestureDescription?) = latch.countDown()
                }, null
            )
            if (!ok) latch.countDown()
        }
        try {
            latch.await(dur + 2000, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            runner?.stopped = true
        }
    }

    private fun buildGesture(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long): GestureDescription {
        val path = Path().apply {
            moveTo(x1, y1)
            if (hypot(x2 - x1, y2 - y1) > 10f) lineTo(x2, y2) else lineTo(x1 + 0.1f, y1)
        }
        return GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur.coerceIn(1, 60_000)))
            .build()
    }

    // ---------- stop ----------

    fun stopAll() {
        wizard?.dismiss()
        closeRegion()
        if (recording) finishRecording()
        if (picking) {
            picking = false
            removeLayer()
        }
        runner?.stopped = true
        abstractEngine?.stopped = true
        worker?.interrupt()
        updateButtons()
    }

    // ---------- Abstract Builder glue ----------

    private var abstractEngine: AbstractEngine? = null

    /** Runs [body] on a worker thread with a fresh engine, after [delaySec] seconds so you can switch to the game. */
    fun runAbstract(delaySec: Int, body: (AbstractEngine) -> Unit) {
        if (worker?.isAlive == true) {
            toast("Already running. Press Stop first")
            return
        }
        stopAll()
        val eng = AbstractEngine(this, AbstractStore.load(this))
        abstractEngine = eng
        isRunning = true
        Capture.reels = 0
        Capture.status = "Starting"
        handler.removeCallbacks(ticker)
        handler.post(ticker)
        worker = Thread {
            try {
                if (delaySec > 0) {
                    ExecLog.add("Starting in $delaySec s, switch to your game")
                    eng.waitSeconds(delaySec)
                }
                if (!eng.stopped) {
                    eng.resetVars()
                    body(eng)
                }
            } catch (e: Exception) {
                ExecLog.add("⚠ Error: ${e.message}")
            } finally {
                isRunning = false
                updateButtons()
            }
        }.also {
            it.isDaemon = true
            it.start()
        }
        updateButtons()
    }

    /** Starts the project's main job (or [jobName]). */
    fun startAbstract(jobName: String?, delaySec: Int) {
        val project = AbstractStore.load(this)
        val name = jobName ?: project.mainJob
        runAbstract(delaySec) { it.runFrom(name) }
    }

    /** Lets the person drag a rectangle on screen; the result is [left, top, right, bottom]. */
    fun pickRegion(start: IntArray?, onDone: (IntArray?) -> Unit) {
        closeRegion()
        val st = if (start != null && start[2] > start[0] && start[3] > start[1]) {
            Rect(start[0], start[1], start[2], start[3])
        } else {
            Rect(600, 300, 1400, 700)
        }
        val v = RegionView(this, st)
        v.title = "Drag the box over the area to watch,\ndrag the corner triangle to resize, then Save"
        v.listener = object : RegionView.Listener {
            override fun onSave(r: Rect) {
                closeRegion()
                onDone(intArrayOf(r.left, r.top, r.right, r.bottom))
            }

            override fun onCancel() {
                closeRegion()
                onDone(null)
            }
        }
        regionView = v
        addFullOverlay(v)
    }
}
