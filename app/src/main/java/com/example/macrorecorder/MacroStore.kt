package com.example.macrorecorder

import android.content.Context

/** Saves the script text and settings in SharedPreferences. */
object MacroStore {
    private const val PREFS = "macro"
    private const val KEY_SCRIPT = "script"
    private const val KEY_LOOPS = "loops"
    private const val KEY_SPEED = "speed"

    val EXAMPLE = """
# Macro script. One command per line. Lines starting with # are comments.
# Use the "Pick" button on the floating panel to get X Y and color values.
#
# Cast: hold the screen for 1 second
hold 1200 600 1000
wait 1500
#
# Wait for a button color, then tap it (needs screen capture):
# findtap FFFFFF 20 8000
#
# Reel minigame: keep the bar color under the fish color (needs screen capture):
# track FFFFFF 3A6EA5 400 900 1500 960 1200 600 15000 25
wait 1000
""".trimIndent() + "\n"

    /** Hand-editable starting point for Roblox Fisch. The 🐟 wizard on the panel fills in real values. */
    val FISCH_TEMPLATE = """
# Fisch auto-fish template. Easiest way: use the 🐟 button on the floating panel instead,
# it measures everything for you and writes this script.
loop 0
  # cast (hold to charge, release at full power)
  hold 540 1500 1100
  wait 600
  # shake: tap the saved shake picture until the reel strip shows up in the box
  untilreel 740 880 1660 925 30000
    findimg shake 300 30
  end
  # reel: box around the dark reel strip, then where to press
  reel 740 880 1660 925 1200 600 60000
  wait 1500
end
""".trimIndent() + "\n"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun script(c: Context): String = prefs(c).getString(KEY_SCRIPT, null) ?: EXAMPLE

    fun setScript(c: Context, text: String) {
        prefs(c).edit().putString(KEY_SCRIPT, text).apply()
    }

    /** 0 means repeat until stopped. */
    var Context.loops: Int
        get() = prefs(this).getInt(KEY_LOOPS, 1)
        set(v) = prefs(this).edit().putInt(KEY_LOOPS, v).apply()

    var Context.speed: Float
        get() = prefs(this).getFloat(KEY_SPEED, 1f)
        set(v) = prefs(this).edit().putFloat(KEY_SPEED, v).apply()
}
