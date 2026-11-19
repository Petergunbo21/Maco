package com.example.macrorecorder

import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The live execution log shown in the Abstract Builder. */
object ExecLog {
    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Volatile
    var listener: (() -> Unit)? = null

    fun add(msg: String) {
        synchronized(lines) {
            lines.add("${fmt.format(Date())}  $msg")
            if (lines.size > 300) lines.removeAt(0)
        }
        listener?.invoke()
    }

    fun tail(n: Int): String = synchronized(lines) { lines.takeLast(n).joinToString("\n") }

    fun clear() {
        synchronized(lines) { lines.clear() }
        listener?.invoke()
    }
}

/**
 * Runs an Abstract Builder project. See Abstract.kt for how jobs and rules work.
 * Everything runs on the thread that calls [runFrom] / [exec] / [evalCond].
 */
class AbstractEngine(private val svc: MacroService, private val project: Project) {

    @Volatile
    var stopped = false

    private val vars = HashMap<String, Any>()
    private var depth = 0

    fun log(msg: String) {
        ExecLog.add(msg)
        Capture.status = msg.take(46)
    }

    private fun pause(ms: Long) {
        val end = SystemClock.uptimeMillis() + ms
        while (!stopped && SystemClock.uptimeMillis() < end) {
            try {
                Thread.sleep(minOf(50L, maxOf(1L, end - SystemClock.uptimeMillis())))
            } catch (e: InterruptedException) {
                stopped = true
            }
        }
    }

    fun waitSeconds(sec: Int) = pause(sec * 1000L)

    // ---------- variables ----------

    fun resetVars() {
        vars.clear()
        for (v in project.vars) {
            vars[v.name] = when (v.type) {
                "bool" -> v.initial.equals("true", ignoreCase = true)
                "number" -> v.initial.toDoubleOrNull() ?: 0.0
                else -> v.initial
            }
        }
    }

    private fun fmt(v: Any): String = if (v is Double && v == Math.floor(v)) v.toLong().toString() else v.toString()

    private fun setVar(a: Action) {
        val cur = vars[a.name]
        if (cur == null) {
            log("⚠ Variable '${a.name}' doesn't exist (add it under Variables)")
            return
        }
        val next: Any = when (cur) {
            is Boolean -> if (a.op == "toggle") !cur else a.value.equals("true", ignoreCase = true)
            is Double -> {
                val n = a.value.toDoubleOrNull() ?: 0.0
                if (a.op == "add") cur + n else n
            }
            else -> if (a.op == "add") cur.toString() + a.value else a.value
        }
        vars[a.name] = next
        log("${a.name} = ${fmt(next)}")
    }

    // ---------- conditions ----------

    /** Similarity 50..100 % becomes a per-color tolerance for the matcher. */
    private fun tolFor(similarity: Int): Int = ((100 - similarity.coerceIn(50, 100)) * 255 / 100).coerceAtLeast(6)

    private fun evalVar(c: Cond): Boolean {
        val cur = vars[c.varName]
        if (cur == null) {
            log("⚠ Variable '${c.varName}' doesn't exist")
            return false
        }
        return when (cur) {
            is Boolean -> {
                val want = c.value.equals("true", ignoreCase = true)
                if (c.cmp == "!=") cur != want else cur == want
            }
            is Double -> {
                val want = c.value.toDoubleOrNull() ?: return false
                when (c.cmp) {
                    "==" -> cur == want
                    "!=" -> cur != want
                    ">" -> cur > want
                    "<" -> cur < want
                    ">=" -> cur >= want
                    "<=" -> cur <= want
                    else -> false
                }
            }
            else -> if (c.cmp == "!=") cur.toString() != c.value else cur.toString() == c.value
        }
    }

    fun evalCond(c: Cond, verbose: Boolean = false): Boolean {
        val result: Boolean
        if (c.type == "var") {
            result = evalVar(c)
        } else {
            if (Capture.bitmap == null) {
                log("⚠ Screen capture is off. Open the app and tap 'Start screen capture'")
                stopped = true
                return false
            }
            val tpl = Images.load(svc, c.image)
            if (tpl == null) {
                log("⚠ Picture '${c.image}' isn't saved yet")
                result = false
            } else {
                val found = Images.find(tpl, tolFor(c.similarity), c.region) != null
                result = if (c.appears) found else !found
            }
        }
        if (verbose) log("${if (result) "✔" else "✘"} ${AbstractStore.describe(c)}")
        return result
    }

    fun evalGroup(g: CondGroup, verbose: Boolean = false): Boolean {
        if (g.conds.isEmpty()) return true
        if (g.mode == "OR") {
            for (c in g.conds) {
                if (stopped) return false
                if (evalCond(c, verbose)) return true
            }
            return false
        }
        for (c in g.conds) {
            if (stopped) return false
            if (!evalCond(c, verbose)) return false
        }
        return true
    }

    // ---------- actions ----------

    fun exec(a: Action) {
        if (stopped) return
        when (a.type) {
            "tap" -> {
                log("Tap (${a.x1}, ${a.y1})")
                svc.gestureBlocking(a.x1, a.y1, a.x1, a.y1, 50)
            }
            "tapimg" -> {
                val tpl = Images.load(svc, a.image)
                if (tpl == null) {
                    log("⚠ Picture '${a.image}' isn't saved yet")
                    return
                }
                val hit = Images.find(tpl, tolFor(a.similarity), a.region)
                if (hit == null) {
                    log("Image '${a.image}' not found, nothing tapped")
                } else {
                    log("Tap image '${a.image}' at (${hit.x}, ${hit.y})")
                    svc.gestureBlocking(hit.x, hit.y, hit.x, hit.y, 50)
                }
            }
            "swipe" -> {
                val dur = if (a.style == "drag") maxOf(a.ms, 400) else a.ms
                log(AbstractStore.describe(a))
                svc.gestureBlocking(a.x1, a.y1, a.x2, a.y2, dur.toLong())
            }
            "wait" -> {
                log("Wait ${a.ms} ms")
                pause(a.ms.toLong())
            }
            "setvar" -> setVar(a)
            "runjob" -> {
                log("→ Run job '${a.name}'")
                runJob(a.name)
            }
            "custom" -> runCustom(a.name)
        }
    }

    fun runActions(list: List<Action>) {
        for (a in list) {
            if (stopped) return
            exec(a)
            if (!stopped) pause(project.stepDelayMs.toLong())
        }
    }

    private fun runCustom(name: String) {
        val ca = project.custom(name)
        if (ca == null) {
            log("⚠ Custom action '$name' not found")
            return
        }
        if (depth >= 8) {
            log("⚠ Nested too deeply, skipping '$name'")
            return
        }
        depth++
        try {
            log("→ Custom action '$name'")
            runActions(ca.actions)
        } finally {
            depth--
        }
    }

    // ---------- jobs ----------

    private fun usesImages(): Boolean {
        fun act(a: Action) = a.type == "tapimg"
        for (j in project.jobs) for (r in j.rules) {
            if (r.cond.conds.any { it.type == "image" } || r.actions.any { act(it) }) return true
        }
        return project.customs.any { c -> c.actions.any { act(it) } }
    }

    /** Starts a fresh run of the named job (variables are reset to their starting values). */
    fun runFrom(jobName: String) {
        resetVars()
        if (usesImages() && Capture.bitmap == null) {
            log("⚠ Screen capture is off. Open the app and tap 'Start screen capture'")
            return
        }
        log("=== Start job '$jobName' ===")
        runJob(jobName)
        log(if (stopped) "=== Stopped ===" else "=== Finished ===")
    }

    fun runJob(name: String) {
        val job = project.job(name)
        if (job == null) {
            log("⚠ Job '$name' not found")
            return
        }
        if (depth >= 8) {
            log("⚠ Jobs nested too deeply, skipping '$name'")
            return
        }
        depth++
        try {
            execJob(job)
        } finally {
            depth--
        }
    }

    private fun execJob(job: Job) {
        log("▶ Job '${job.name}' (${if (job.scope == Scope.ONCE) "once" else "continuous"})")
        if (job.scope == Scope.ONCE) {
            for (rule in job.rules) {
                if (stopped) return
                if (!rule.enabled) continue
                if (evalGroup(rule.cond)) {
                    log("Rule '${rule.name}' matched")
                    runActions(rule.actions)
                } else {
                    log("Rule '${rule.name}' skipped")
                }
            }
            return
        }

        var fails = 0
        while (!stopped) {
            var matched = false
            for (rule in job.rules) {
                if (stopped) return
                if (!rule.enabled) continue
                if (evalGroup(rule.cond)) {
                    log("Rule '${rule.name}' matched")
                    runActions(rule.actions)
                    matched = true
                    break // top rules have priority: start the next pass from the top
                }
            }
            if (stopped) return
            if (matched) {
                fails = 0
            } else {
                fails++
                if (job.failLimit > 0) log("Nothing matched ($fails/${job.failLimit})")
                if (job.failLimit > 0 && fails >= job.failLimit) {
                    if (job.recoveryJob.isNotBlank()) {
                        log("⚠ Watchdog: running recovery job '${job.recoveryJob}'")
                        runJob(job.recoveryJob)
                    } else {
                        log("⚠ Watchdog: no recovery job set, restarting '${job.name}'")
                    }
                    if (job.resetVarsOnRestart) resetVars()
                    fails = 0
                    log("↻ Restarting job '${job.name}'")
                    continue
                }
            }
            pause(job.loopDelayMs.toLong())
        }
    }
}
