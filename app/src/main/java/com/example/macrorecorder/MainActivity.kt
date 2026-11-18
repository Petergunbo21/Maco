package com.example.macrorecorder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.SeekBar
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.macrorecorder.MacroStore.loops
import com.example.macrorecorder.MacroStore.speed

class MainActivity : Activity() {

    companion object {
        private const val REQ_CAPTURE = 1
        private const val REQ_IMPORT = 2
        private const val REQ_EXPORT = 3
    }

    private lateinit var status: TextView
    private lateinit var editor: EditText
    private lateinit var stepsBox: LinearLayout
    private lateinit var rodName: EditText
    private lateinit var castInput: EditText
    private lateinit var sensBar: SeekBar
    private lateinit var sensLabel: TextView
    private lateinit var loopsInput: EditText
    private lateinit var speedInput: EditText
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
        }

        fun label(t: String) = TextView(this).apply { text = t; setPadding(0, 28, 0, 8) }
        fun button(t: String, onClick: (View) -> Unit) =
            Button(this).apply { text = t; setOnClickListener(onClick) }

        root.addView(TextView(this).apply { text = "Macro Recorder"; textSize = 26f })
        status = TextView(this).apply { setPadding(0, 20, 0, 20) }
        root.addView(status)

        root.addView(button("1. Enable accessibility service") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(button("2. Start screen capture (for color detection)") {
            val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
        })
        root.addView(button("3. Show floating panel") {
            MacroService.instance?.showPanel()
            refresh()
        })
        root.addView(button("Stop screen capture") {
            startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP))
            status.postDelayed({ refresh() }, 600)
        })

        root.addView(label(
            "FISCH QUICK START: do steps 1 to 3, open Fisch, then use the 🐟 button on the panel. " +
                "Round 1/2: cast and tap 🐟 1/2 while the SHAKE button is showing. " +
                "Round 2/2: tap 🐟 2/2 while the reel bar is showing. Then tap ▶ Play from the idle screen."
        ))

        // ---- visual step list (edits the script below) ----
        root.addView(label("STEPS: tap ✎ to change a step, ↑ ↓ to move it, ✕ to delete it. Changes save automatically."))
        stepsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(stepsBox)
        root.addView(button("+ Add step") { addStep() })
        root.addView(button("Copy script") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("macro", editor.text.toString()))
            toast("Script copied")
        })
        root.addView(button("Paste script") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = cm.primaryClip
            val t = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
            if (t.isBlank()) toast("Clipboard is empty") else {
                applyLines(t.split("\n"))
                toast("Pasted and saved")
            }
        })

        // ---- rod settings ----
        root.addView(label("ROD SETTINGS (each rod can have its own cast time and reel sensitivity)"))
        val rod = MacroStore.activeRod(this)
        rodName = EditText(this).apply { hint = "Rod name"; setText(rod.name) }
        root.addView(rodName)
        root.addView(label("Cast hold time in ms (how long to hold before letting go)"))
        castInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(rod.holdMs.toString())
        }
        root.addView(castInput)
        sensLabel = label("")
        root.addView(sensLabel)
        sensBar = SeekBar(this).apply {
            max = 15 // 0.5 to 2.0 in steps of 0.1
            progress = ((rod.sens - 0.5f) / 0.1f).toInt().coerceIn(0, 15)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) = updateSensLabel()
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        root.addView(sensBar)
        updateSensLabel()
        root.addView(CheckBox(this).apply {
            text = "Show the green/red detection arrows while running"
            isChecked = MacroStore.overlayOn(this@MainActivity)
            setOnCheckedChangeListener { _, on -> MacroStore.setOverlay(this@MainActivity, on) }
        })
        root.addView(button("Save rod settings") {
            val r = MacroStore.Rod(
                rodName.text.toString(),
                castInput.text.toString().toIntOrNull()?.coerceIn(100, 10000) ?: 1100,
                sensValue()
            )
            MacroStore.saveRod(this, r)
            toast("Saved '${r.name}' and set it as the active rod")
        })
        root.addView(button("Choose saved rod") {
            val list = MacroStore.rods(this)
            if (list.isEmpty()) toast("No saved rods yet. Fill in the settings and tap Save rod settings") else {
                AlertDialog.Builder(this)
                    .setTitle("Choose rod")
                    .setItems(list.map { it.name }.toTypedArray()) { _, which ->
                        val r = list[which]
                        MacroStore.setActiveRod(this, r.name)
                        rodName.setText(r.name)
                        castInput.setText(r.holdMs.toString())
                        sensBar.progress = ((r.sens - 0.5f) / 0.1f).toInt().coerceIn(0, 15)
                        toast("Using '${r.name}'")
                    }
                    .show()
            }
        })

        root.addView(label("Repeat count (0 = until you press Stop)"))
        loopsInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(loops.toString())
        }
        root.addView(loopsInput)

        root.addView(label("Speed for 'wait' (1.0 = normal, 2.0 = twice as fast)"))
        speedInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            setText(speed.toString())
        }
        root.addView(speedInput)
        root.addView(button("Save repeat + speed") {
            loops = loopsInput.text.toString().toIntOrNull()?.coerceAtLeast(0) ?: 1
            speed = speedInput.text.toString().toFloatOrNull()?.coerceIn(0.1f, 10f) ?: 1f
            refresh()
        })

        root.addView(label("Script text (advanced, the steps above are the same thing)"))
        editor = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            gravity = Gravity.TOP
            minLines = 12
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    dirty = true
                    rebuildSteps()
                }
            })
        }
        root.addView(editor)
        setEditor(MacroStore.script(this))

        root.addView(button("Save script") {
            MacroStore.setScript(this, editor.text.toString())
            dirty = false
            toast("Script saved")
        })
        root.addView(button("Check script for mistakes") {
            try {
                ScriptParser.parse(editor.text.toString())
                toast("Script looks good")
            } catch (e: ScriptError) {
                toast(e.message ?: "Script error")
            }
        })
        root.addView(button("Import script file") {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }, REQ_IMPORT
            )
        })
        root.addView(button("Export script file") {
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TITLE, "macro.txt")
                }, REQ_EXPORT
            )
        })
        root.addView(button("Load Fisch template script") { setEditor(MacroStore.FISCH_TEMPLATE); dirty = true })
        root.addView(button("Reset to example script") { setEditor(MacroStore.EXAMPLE) })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        // Pick up a script recorded from the floating panel, unless you are mid-edit.
        if (!dirty) setEditor(MacroStore.script(this))
        refresh()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data == null) return
        when (requestCode) {
            REQ_CAPTURE -> {
                val i = Intent(this, CaptureService::class.java)
                    .putExtra("code", resultCode)
                    .putExtra("data", data)
                startForegroundService(i)
                status.postDelayed({ refresh() }, 1000)
            }
            REQ_IMPORT -> {
                val text = data.data?.let { uri ->
                    contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }
                if (text == null) toast("Couldn't read that file") else {
                    setEditor(text)
                    dirty = true
                    toast("Imported. Tap Save script to keep it")
                }
            }
            REQ_EXPORT -> {
                data.data?.let { uri ->
                    contentResolver.openOutputStream(uri)?.use {
                        it.write(editor.text.toString().toByteArray())
                    }
                    toast("Exported")
                }
            }
        }
    }

    // ---------- visual step list ----------

    private fun sensValue(): Float = 0.5f + sensBar.progress * 0.1f

    private fun updateSensLabel() {
        sensLabel.text = "Reel sensitivity: ${"%.1f".format(sensValue())}  (higher = the bar chases the fish harder; lower = calmer)"
    }

    private fun isBlankOrComment(raw: String): Boolean {
        val s = raw.trim()
        return s.isEmpty() || s.startsWith("#") || s.startsWith("//")
    }

    private fun currentLines(): MutableList<String> = editor.text.toString().split("\n").toMutableList()

    /** Writes new script lines to the editor, saves them, and redraws the step list. */
    private fun applyLines(lines: List<String>) {
        val text = lines.joinToString("\n")
        editor.setText(text)
        MacroStore.setScript(this, text)
        dirty = false
        rebuildSteps()
    }

    private fun describe(raw: String): String {
        val t = raw.trim().split(Regex("\\s+"))
        fun n(i: Int): String = t.getOrNull(i) ?: "?"
        return when (t[0].lowercase()) {
            "cast" -> "Cast (hold for the rod's cast time)"
            "tap" -> "Tap at (${n(1)}, ${n(2)})"
            "hold" -> "Hold at (${n(1)}, ${n(2)}) for ${n(3)} ms"
            "swipe" -> "Swipe (${n(1)}, ${n(2)}) to (${n(3)}, ${n(4)}) in ${n(5)} ms"
            "wait" -> "Wait ${n(1)} ms"
            "findimg" -> "Find picture '${n(1)}' and tap it"
            "waitimg" -> "Wait until picture '${n(1)}' shows"
            "waitgone" -> "Wait until picture '${n(1)}' disappears"
            "ifimg" -> "If picture '${n(1)}' is on screen:"
            "reel" -> "Reel the fish automatically"
            "untilreel" -> "Repeat these until the reel bar appears:"
            "track" -> "Reel using colors"
            "until" -> "Repeat these until a color appears:"
            "loop" -> if (n(1) == "0") "Repeat forever:" else "Repeat ${n(1)} times:"
            "ifcolor" -> "If a color matches:"
            "waitcolor" -> "Wait for a color"
            "findtap" -> "Find a color and tap it"
            "end" -> "End"
            else -> raw.trim()
        }
    }

    private fun rebuildSteps() {
        if (!::stepsBox.isInitialized || !::editor.isInitialized) return
        stepsBox.removeAllViews()
        var depth = 0
        var shown = 0
        val openers = setOf("loop", "until", "untilreel", "ifcolor", "ifimg")
        for ((i, raw) in currentLines().withIndex()) {
            if (isBlankOrComment(raw)) continue
            val op = raw.trim().split(Regex("\\s+"))[0].lowercase()
            if (op == "end" && depth > 0) depth--
            stepsBox.addView(stepRow(i, describe(raw), depth))
            shown++
            if (op in openers) depth++
        }
        if (shown == 0) {
            stepsBox.addView(TextView(this).apply { text = "No steps yet. Tap + Add step, or use the 🐟 setup on the panel." })
        }
    }

    private fun stepRow(index: Int, text: String, depth: Int): View {
        fun small(t: String, onClick: () -> Unit) = Button(this).apply {
            this.text = t
            minWidth = 0
            minimumWidth = 0
            setPadding(24, 8, 24, 8)
            setOnClickListener { onClick() }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(depth * 36, 4, 0, 4)
        }
        row.addView(
            TextView(this).apply { this.text = text; textSize = 15f },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(small("↑") { moveStep(index, -1) })
        row.addView(small("↓") { moveStep(index, 1) })
        row.addView(small("✎") { editStep(index) })
        row.addView(small("✕") { deleteStep(index) })
        return row
    }

    private fun moveStep(i: Int, dir: Int) {
        val lines = currentLines()
        var j = i + dir
        while (j in lines.indices && isBlankOrComment(lines[j])) j += dir
        if (i !in lines.indices || j !in lines.indices) return
        val tmp = lines[i]
        lines[i] = lines[j]
        lines[j] = tmp
        applyLines(lines)
    }

    private fun deleteStep(i: Int) {
        val lines = currentLines()
        if (i !in lines.indices) return
        lines.removeAt(i)
        applyLines(lines)
    }

    private fun editStep(i: Int) {
        val lines = currentLines()
        if (i !in lines.indices) return
        val input = EditText(this).apply {
            typeface = Typeface.MONOSPACE
            setText(lines[i].trim())
            setSelection(length())
        }
        AlertDialog.Builder(this)
            .setTitle("Edit step")
            .setMessage("Change the numbers or text, then Save.")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                lines[i] = input.text.toString().replace("\n", " ").trim()
                applyLines(lines)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private val addOptions = listOf(
        "Cast" to "cast 540 1500",
        "Tap" to "tap 540 1200",
        "Hold" to "hold 540 1200 1000",
        "Wait" to "wait 1000",
        "Swipe" to "swipe 300 1200 900 1200 400",
        "Find picture and tap it" to "findimg shake 5000 30",
        "Wait until picture shows" to "waitimg shake 10000 30",
        "Wait until picture disappears" to "waitgone shake 10000 30",
        "If picture is on screen" to "ifimg shake 30\n  tap 540 1200\nend",
        "Reel the fish (auto)" to "reel 740 880 1660 925 1200 600 60000",
        "Wait for the reel bar" to "untilreel 740 880 1660 925 30000\n  wait 200\nend",
        "Repeat forever" to "loop 0\n  wait 1000\nend"
    )

    private fun addStep() {
        AlertDialog.Builder(this)
            .setTitle("Add step")
            .setItems(addOptions.map { it.first }.toTypedArray()) { _, which ->
                val lines = currentLines()
                while (lines.isNotEmpty() && lines.last().isBlank()) lines.removeAt(lines.size - 1)
                // Inside a top-level loop? Put the new step before its closing 'end'.
                var at = lines.size
                if (lines.isNotEmpty() && lines.last().trim().equals("end", ignoreCase = true)) at = lines.size - 1
                lines.addAll(at, addOptions[which].second.split("\n"))
                applyLines(lines)
                editStep(at)
            }
            .show()
    }

    private fun setEditor(text: String) {
        editor.setText(text)
        dirty = false
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun serviceEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(this, MacroService::class.java).flattenToString()
        return enabled.split(':').any { it.equals(me, ignoreCase = true) }
    }

    private fun refresh() {
        status.text = (if (serviceEnabled()) "Accessibility service: ON" else "Accessibility service: OFF (do step 1)") +
            "\n" + (if (Capture.bitmap != null) "Screen capture: ON" else "Screen capture: OFF (only needed for color commands)") +
            "\nRepeat: ${if (loops == 0) "until stopped" else loops}   Speed: $speed"
    }
}
