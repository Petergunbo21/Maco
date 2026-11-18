package com.example.macrorecorder

import android.Manifest
import android.app.Activity
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

        root.addView(label("Script (recording replaces this; see the README for commands)"))
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
                override fun afterTextChanged(s: Editable?) { dirty = true }
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
