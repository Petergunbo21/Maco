package com.example.macrorecorder

import android.content.Context

/** Saves the script text and settings in SharedPreferences. */
object MacroStore {
    private const val PREFS = "macro"
    private const val KEY_SCRIPT = "script"
    private const val KEY_LOOPS = "loops"
    private const val KEY_SPEED = "speed"
    private const val KEY_RODS = "rods"
    private const val KEY_ROD = "rod"
    private const val KEY_OVERLAY = "overlay"

    /** Per-rod settings: how long to hold to cast, and how eagerly the reel chases the fish. */
    class Rod(val name: String, val holdMs: Int, val sens: Float)

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
  # cast: holds for the active rod's cast time (set it under Rod settings)
  cast 540 1500
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

    fun script(c: Context): String = prefs(c).getString(KEY_SCRIPT, null) ?: FISCH_TEMPLATE

    /** True for the built-in example/template text, which has no real positions for this phone yet. */
    fun isPlaceholder(text: String): Boolean =
        text.contains("Macro script. One command per line") || text.contains("Fisch auto-fish template")

    fun setScript(c: Context, text: String) {
        prefs(c).edit().putString(KEY_SCRIPT, text).apply()
    }

    fun rods(c: Context): List<Rod> {
        val raw = prefs(c).getString(KEY_RODS, "") ?: ""
        return raw.split("\n").mapNotNull { line ->
            val p = line.split("|")
            val h = p.getOrNull(1)?.toIntOrNull()
            val s = p.getOrNull(2)?.toFloatOrNull()
            if (p.size < 3 || p[0].isBlank() || h == null || s == null) null else Rod(p[0], h, s)
        }
    }

    private fun storeRods(c: Context, list: List<Rod>) {
        prefs(c).edit().putString(KEY_RODS, list.joinToString("\n") { "${it.name}|${it.holdMs}|${it.sens}" }).apply()
    }

    /** Saves (or replaces) a rod and makes it the active one. */
    fun saveRod(c: Context, rod: Rod) {
        val clean = Rod(rod.name.replace("|", " ").replace("\n", " ").trim().ifEmpty { "Default" }, rod.holdMs, rod.sens)
        storeRods(c, rods(c).filter { it.name != clean.name } + clean)
        prefs(c).edit().putString(KEY_ROD, clean.name).apply()
    }

    fun setActiveRod(c: Context, name: String) {
        prefs(c).edit().putString(KEY_ROD, name).apply()
    }

    fun activeRod(c: Context): Rod {
        val n = prefs(c).getString(KEY_ROD, null)
        return rods(c).firstOrNull { it.name == n } ?: Rod("Default", 1100, 1f)
    }

    fun overlayOn(c: Context): Boolean = prefs(c).getBoolean(KEY_OVERLAY, true)

    fun setOverlay(c: Context, on: Boolean) {
        prefs(c).edit().putBoolean(KEY_OVERLAY, on).apply()
    }

    /** 0 means repeat until stopped. */
    var Context.loops: Int
        get() = prefs(this).getInt(KEY_LOOPS, 1)
        set(v) = prefs(this).edit().putInt(KEY_LOOPS, v).apply()

    var Context.speed: Float
        get() = prefs(this).getFloat(KEY_SPEED, 1f)
        set(v) = prefs(this).edit().putFloat(KEY_SPEED, v).apply()
}
