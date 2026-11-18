package com.example.macrorecorder

/** One command in a macro script. */
sealed class Cmd {
    data class Tap(val x: Int, val y: Int) : Cmd()
    data class Hold(val x: Int, val y: Int, val ms: Long) : Cmd()
    data class Swipe(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val ms: Long) : Cmd()
    data class Wait(val ms: Long) : Cmd()
    data class WaitColor(val x: Int, val y: Int, val rgb: Int, val tol: Int, val timeout: Long) : Cmd()
    data class FindTap(val rgb: Int, val tol: Int, val timeout: Long) : Cmd()
    data class Track(
        val fish: Int, val bar: Int,
        val x1: Int, val y1: Int, val x2: Int, val y2: Int,
        val px: Int, val py: Int,
        val ms: Long, val tol: Int, val invert: Boolean
    ) : Cmd()
    data class Loop(val count: Int, val body: List<Cmd>) : Cmd()
    data class IfColor(val x: Int, val y: Int, val rgb: Int, val tol: Int, val body: List<Cmd>) : Cmd()
}

/** A mistake in the script text. */
class ScriptError(line: Int, msg: String) : Exception("Line $line: $msg")

/** A problem while the script is running (for example screen capture is off). */
class RunError(msg: String) : Exception(msg)

/**
 * Script format (one command per line, lines starting with # or // are comments):
 *
 *   tap X Y
 *   hold X Y MS
 *   swipe X1 Y1 X2 Y2 MS
 *   wait MS
 *   waitcolor X Y RRGGBB [TOL] [TIMEOUT_MS]     wait until that pixel has that color
 *   findtap RRGGBB [TOL] [TIMEOUT_MS]           find the color anywhere on screen and tap it
 *   ifcolor X Y RRGGBB [TOL]  ...  end          run the lines inside only if the pixel matches
 *   loop N  ...  end                            repeat N times (0 = forever)
 *   track FISH BAR X1 Y1 X2 Y2 PRESSX PRESSY MAXMS [TOL] [invert]
 *        keeps the bar color under the fish color by pressing at PRESSX PRESSY
 */
object ScriptParser {

    private class Frame(val line: Int, val build: (List<Cmd>) -> Cmd) {
        val body = mutableListOf<Cmd>()
    }

    fun parse(text: String): List<Cmd> {
        val root = mutableListOf<Cmd>()
        val stack = ArrayList<Frame>()
        fun target(): MutableList<Cmd> = if (stack.isEmpty()) root else stack.last().body

        text.lines().forEachIndexed { index, raw ->
            val ln = index + 1
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) return@forEachIndexed
            val t = line.split(Regex("\\s+"))
            val op = t[0].lowercase()

            fun num(k: Int, what: String): Int =
                t.getOrNull(k)?.toIntOrNull() ?: throw ScriptError(ln, "'$op' needs $what")

            fun opt(k: Int, def: Int): Int = t.getOrNull(k)?.toIntOrNull() ?: def

            fun color(k: Int): Int {
                val s = t.getOrNull(k)?.removePrefix("#")
                if (s == null || s.length != 6) throw ScriptError(ln, "'$op' needs a color like FF8800")
                return s.toIntOrNull(16) ?: throw ScriptError(ln, "bad color '$s'")
            }

            when (op) {
                "tap" -> target().add(Cmd.Tap(num(1, "x"), num(2, "y")))
                "hold" -> target().add(Cmd.Hold(num(1, "x"), num(2, "y"), num(3, "milliseconds").toLong()))
                "swipe" -> target().add(
                    Cmd.Swipe(num(1, "x1"), num(2, "y1"), num(3, "x2"), num(4, "y2"), num(5, "milliseconds").toLong())
                )
                "wait" -> target().add(Cmd.Wait(num(1, "milliseconds").toLong()))
                "waitcolor" -> target().add(
                    Cmd.WaitColor(num(1, "x"), num(2, "y"), color(3), opt(4, 20), opt(5, 10000).toLong())
                )
                "findtap" -> target().add(Cmd.FindTap(color(1), opt(2, 20), opt(3, 10000).toLong()))
                "track" -> {
                    var tol = 25
                    var invert = false
                    for (k in 10 until t.size) {
                        if (t[k].equals("invert", ignoreCase = true)) invert = true
                        else tol = t[k].toIntOrNull() ?: throw ScriptError(ln, "unexpected '${t[k]}'")
                    }
                    target().add(
                        Cmd.Track(
                            color(1), color(2),
                            num(3, "x1"), num(4, "y1"), num(5, "x2"), num(6, "y2"),
                            num(7, "press x"), num(8, "press y"),
                            num(9, "max milliseconds").toLong(), tol, invert
                        )
                    )
                }
                "loop" -> {
                    val n = num(1, "a repeat count (0 = forever)")
                    stack.add(Frame(ln) { body -> Cmd.Loop(n, body) })
                }
                "ifcolor" -> {
                    val x = num(1, "x"); val y = num(2, "y"); val c = color(3); val tol = opt(4, 20)
                    stack.add(Frame(ln) { body -> Cmd.IfColor(x, y, c, tol, body) })
                }
                "end" -> {
                    if (stack.isEmpty()) throw ScriptError(ln, "'end' without loop or ifcolor")
                    val f = stack.removeAt(stack.lastIndex)
                    target().add(f.build(f.body))
                }
                else -> throw ScriptError(ln, "unknown command '$op'")
            }
        }
        if (stack.isNotEmpty()) throw ScriptError(stack.last().line, "missing 'end'")
        return root
    }
}
