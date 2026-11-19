package com.example.macrorecorder

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/*
 * Abstract Builder model.
 *
 * Project
 *  ├─ Jobs            sub-workflows. A job is an ordered list of Rules.
 *  │   └─ Rule        "WHEN <condition group> DO <actions>"
 *  ├─ Custom actions  named groups of actions that can be reused like a single action
 *  └─ Variables       boolean / number / string values shared by all jobs
 *
 * A job runs in one of two scopes:
 *  - ONCE:       goes through its rules top to bottom one time, running every rule whose condition is true
 *  - CONTINUOUS: repeats forever; each pass runs only the FIRST rule (top = highest priority) whose condition is
 *                true. If a pass matches nothing it counts as a failure; N failures in a row start the recovery job
 *                and then restart the job cleanly.
 */

enum class Scope { ONCE, CONTINUOUS }

class Cond(
    var type: String = "image",              // "image" or "var"
    var image: String = "",
    var appears: Boolean = true,             // true = Image Appears, false = Image Does Not Appear
    var region: IntArray = intArrayOf(0, 0, 0, 0), // left, top, right, bottom; all zero = whole screen
    var similarity: Int = 85,                // 50..100
    var varName: String = "",
    var cmp: String = "==",                  // == != > < >= <=
    var value: String = ""
)

class CondGroup(var mode: String = "AND", val conds: MutableList<Cond> = mutableListOf())

class Action(
    var type: String = "tap",                // tap, tapimg, swipe, wait, setvar, runjob, custom
    var x1: Int = 540,
    var y1: Int = 1200,
    var x2: Int = 540,
    var y2: Int = 800,
    var ms: Int = 500,                       // wait length, or swipe duration
    var style: String = "swipe",             // swipe (quick flick) or drag (slow joystick-style drag)
    var image: String = "",
    var region: IntArray = intArrayOf(0, 0, 0, 0),
    var similarity: Int = 85,
    var name: String = "",                   // job name, custom action name, or variable name
    var op: String = "set",                  // set, add, toggle (for setvar)
    var value: String = ""
)

class Rule(
    var name: String = "New rule",
    var enabled: Boolean = true,
    val cond: CondGroup = CondGroup(),
    val actions: MutableList<Action> = mutableListOf()
)

class Job(
    var name: String,
    var scope: Scope = Scope.ONCE,
    var loopDelayMs: Int = 300,
    var failLimit: Int = 0,                  // 0 = watchdog off
    var recoveryJob: String = "",
    var resetVarsOnRestart: Boolean = false,
    val rules: MutableList<Rule> = mutableListOf()
)

class VarDef(var name: String, var type: String = "number", var initial: String = "0") // bool, number, string

class CustomAction(var name: String, val actions: MutableList<Action> = mutableListOf())

class Project(
    var name: String = "My macro",
    var mainJob: String = "Main",
    var stepDelayMs: Int = 150,
    val jobs: MutableList<Job> = mutableListOf(),
    val customs: MutableList<CustomAction> = mutableListOf(),
    val vars: MutableList<VarDef> = mutableListOf()
) {
    fun job(name: String): Job? = jobs.firstOrNull { it.name == name }
    fun custom(name: String): CustomAction? = customs.firstOrNull { it.name == name }
}

object AbstractStore {
    private const val PREFS = "abstract"
    private const val KEY = "project"

    fun load(c: Context): Project {
        val raw = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        if (raw != null) {
            try {
                return fromJson(JSONObject(raw))
            } catch (e: Exception) {
                // fall through to a fresh project
            }
        }
        return starter()
    }

    fun save(c: Context, p: Project) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, toJson(p).toString()).apply()
    }

    /** A new project: a Main job that repeats, and an empty Recovery job. */
    fun starter(): Project {
        val p = Project()
        p.jobs.add(Job("Main", Scope.CONTINUOUS, 300, 0, "Recovery"))
        p.jobs.add(Job("Recovery", Scope.ONCE))
        return p
    }

    // ---------- JSON ----------

    private fun arr(a: IntArray): JSONArray = JSONArray().apply { a.forEach { put(it) } }

    private fun ints(j: JSONArray?): IntArray {
        val out = IntArray(4)
        if (j != null) for (i in 0 until minOf(4, j.length())) out[i] = j.optInt(i)
        return out
    }

    private fun toJson(c: Cond) = JSONObject()
        .put("type", c.type).put("image", c.image).put("appears", c.appears)
        .put("region", arr(c.region)).put("similarity", c.similarity)
        .put("varName", c.varName).put("cmp", c.cmp).put("value", c.value)

    private fun condFrom(j: JSONObject) = Cond(
        j.optString("type", "image"), j.optString("image"), j.optBoolean("appears", true),
        ints(j.optJSONArray("region")), j.optInt("similarity", 85),
        j.optString("varName"), j.optString("cmp", "=="), j.optString("value")
    )

    private fun toJson(a: Action) = JSONObject()
        .put("type", a.type).put("x1", a.x1).put("y1", a.y1).put("x2", a.x2).put("y2", a.y2)
        .put("ms", a.ms).put("style", a.style).put("image", a.image).put("region", arr(a.region))
        .put("similarity", a.similarity).put("name", a.name).put("op", a.op).put("value", a.value)

    private fun actionFrom(j: JSONObject) = Action(
        j.optString("type", "tap"), j.optInt("x1"), j.optInt("y1"), j.optInt("x2"), j.optInt("y2"),
        j.optInt("ms", 500), j.optString("style", "swipe"), j.optString("image"),
        ints(j.optJSONArray("region")), j.optInt("similarity", 85),
        j.optString("name"), j.optString("op", "set"), j.optString("value")
    )

    private fun toJson(r: Rule) = JSONObject()
        .put("name", r.name).put("enabled", r.enabled)
        .put("mode", r.cond.mode)
        .put("conds", JSONArray().apply { r.cond.conds.forEach { put(toJson(it)) } })
        .put("actions", JSONArray().apply { r.actions.forEach { put(toJson(it)) } })

    private fun ruleFrom(j: JSONObject): Rule {
        val r = Rule(j.optString("name", "Rule"), j.optBoolean("enabled", true))
        r.cond.mode = j.optString("mode", "AND")
        j.optJSONArray("conds")?.let { a -> for (i in 0 until a.length()) r.cond.conds.add(condFrom(a.getJSONObject(i))) }
        j.optJSONArray("actions")?.let { a -> for (i in 0 until a.length()) r.actions.add(actionFrom(a.getJSONObject(i))) }
        return r
    }

    fun toJson(p: Project): JSONObject = JSONObject()
        .put("name", p.name).put("mainJob", p.mainJob).put("stepDelayMs", p.stepDelayMs)
        .put("jobs", JSONArray().apply {
            p.jobs.forEach { jb ->
                put(
                    JSONObject().put("name", jb.name).put("scope", jb.scope.name)
                        .put("loopDelayMs", jb.loopDelayMs).put("failLimit", jb.failLimit)
                        .put("recoveryJob", jb.recoveryJob).put("resetVars", jb.resetVarsOnRestart)
                        .put("rules", JSONArray().apply { jb.rules.forEach { put(toJson(it)) } })
                )
            }
        })
        .put("customs", JSONArray().apply {
            p.customs.forEach { ca ->
                put(JSONObject().put("name", ca.name).put("actions", JSONArray().apply { ca.actions.forEach { put(toJson(it)) } }))
            }
        })
        .put("vars", JSONArray().apply {
            p.vars.forEach { put(JSONObject().put("name", it.name).put("type", it.type).put("initial", it.initial)) }
        })

    fun fromJson(j: JSONObject): Project {
        val p = Project(j.optString("name", "My macro"), j.optString("mainJob", "Main"), j.optInt("stepDelayMs", 150))
        j.optJSONArray("jobs")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val scope = try { Scope.valueOf(o.optString("scope", "ONCE")) } catch (e: Exception) { Scope.ONCE }
                val jb = Job(
                    o.optString("name", "Job"), scope, o.optInt("loopDelayMs", 300), o.optInt("failLimit", 0),
                    o.optString("recoveryJob"), o.optBoolean("resetVars", false)
                )
                o.optJSONArray("rules")?.let { ra -> for (k in 0 until ra.length()) jb.rules.add(ruleFrom(ra.getJSONObject(k))) }
                p.jobs.add(jb)
            }
        }
        j.optJSONArray("customs")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val ca = CustomAction(o.optString("name", "Custom"))
                o.optJSONArray("actions")?.let { aa -> for (k in 0 until aa.length()) ca.actions.add(actionFrom(aa.getJSONObject(k))) }
                p.customs.add(ca)
            }
        }
        j.optJSONArray("vars")?.let { a ->
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                p.vars.add(VarDef(o.optString("name"), o.optString("type", "number"), o.optString("initial", "0")))
            }
        }
        if (p.jobs.isEmpty()) p.jobs.addAll(starter().jobs)
        return p
    }

    // ---------- describing things in plain words (used by the editor and the log) ----------

    private fun regionText(r: IntArray) =
        if (r.size == 4 && r[2] > r[0] && r[3] > r[1]) "in (${r[0]},${r[1]})-(${r[2]},${r[3]})" else "anywhere"

    fun describe(c: Cond): String = when (c.type) {
        "var" -> "Variable ${c.varName} ${c.cmp} ${c.value}"
        else -> (if (c.appears) "Image '${c.image}' appears " else "Image '${c.image}' does NOT appear ") +
            "${regionText(c.region)}, ${c.similarity}% match"
    }

    fun describe(a: Action): String = when (a.type) {
        "tap" -> "Tap (${a.x1}, ${a.y1})"
        "tapimg" -> "Tap image '${a.image}' ${regionText(a.region)}, ${a.similarity}% match"
        "swipe" -> (if (a.style == "drag") "Joystick drag" else "Swipe") + " (${a.x1},${a.y1}) → (${a.x2},${a.y2}) over ${a.ms} ms"
        "wait" -> "Wait ${a.ms} ms"
        "setvar" -> when (a.op) {
            "add" -> "${a.name} += ${a.value}"
            "toggle" -> "Toggle ${a.name}"
            else -> "${a.name} = ${a.value}"
        }
        "runjob" -> "Run job '${a.name}'"
        "custom" -> "Run custom action '${a.name}'"
        else -> a.type
    }

    fun describe(g: CondGroup): String =
        if (g.conds.isEmpty()) "Always" else (if (g.mode == "OR") "ANY of " else "ALL of ") + "${g.conds.size} condition(s)"
}
