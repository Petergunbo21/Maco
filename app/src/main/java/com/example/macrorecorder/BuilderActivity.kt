package com.example.macrorecorder

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.Collections

/**
 * Abstract Builder: a visual editor for Projects > Jobs > Rules (WHEN conditions, DO actions),
 * Variables and Custom Actions, with Start/Stop and a live execution log.
 */
class BuilderActivity : Activity() {

    private lateinit var project: Project
    private var activeJob = ""
    private lateinit var root: LinearLayout
    private var logView: TextView? = null
    private val ui = Handler(Looper.getMainLooper())

    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 64, 32, 48)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        project = AbstractStore.load(this)
        if (project.job(activeJob) == null) {
            activeJob = if (project.job(project.mainJob) != null) project.mainJob else project.jobs.firstOrNull()?.name ?: ""
        }
        ExecLog.listener = { ui.post { logView?.text = ExecLog.tail(25) } }
        render()
    }

    override fun onPause() {
        ExecLog.listener = null
        super.onPause()
    }

    // ---------- small helpers ----------

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun svc(): MacroService? {
        val s = MacroService.instance
        if (s == null) toast("Turn on the Macro Recorder accessibility service first")
        return s
    }

    private fun tv(t: String, size: Float = 14f, bold: Boolean = false, color: Int = Color.BLACK) =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(null, Typeface.BOLD)
        }

    private fun btn(t: String, small: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = t
        setAllCaps(false)
        if (small) {
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(10), 0, dp(10), 0)
        }
        setOnClickListener { onClick() }
    }

    private fun hrow(vararg v: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        v.forEach { addView(it) }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply {
            setColor(Color.rgb(238, 241, 245))
            cornerRadius = dp(8).toFloat()
        }
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
    }

    private fun section(t: String) = tv(t, 19f, true, Color.rgb(20, 90, 120)).apply { setPadding(0, dp(22), 0, dp(2)) }

    private fun EditText.int(def: Int): Int = text.toString().trim().toIntOrNull() ?: def

    private fun changed() {
        AbstractStore.save(this, project)
        render()
    }

    private fun <T> rows(parent: LinearLayout, list: MutableList<T>, text: (T) -> String, edit: (T) -> Unit) {
        if (list.isEmpty()) parent.addView(tv("   (empty)", 13f, color = Color.GRAY))
        for (i in list.indices) {
            val item = list[i]
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(tv("${i + 1}. ${text(item)}", 13f), LinearLayout.LayoutParams(0, WRAP, 1f))
            line.addView(btn("↑", true) { if (i > 0) { Collections.swap(list, i, i - 1); changed() } })
            line.addView(btn("↓", true) { if (i < list.size - 1) { Collections.swap(list, i, i + 1); changed() } })
            line.addView(btn("✎", true) { edit(item) })
            line.addView(btn("✕", true) { list.removeAt(i); changed() })
            parent.addView(line)
        }
    }

    // ---------- the form used by every dialog ----------

    private inner class Form {
        val box = LinearLayout(this@BuilderActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        fun label(t: String) {
            box.addView(tv(t, 13f, true, Color.DKGRAY).apply { setPadding(0, dp(12), 0, 0) })
        }

        fun text(init: String, hint: String = "", numeric: Boolean = false): EditText {
            val e = EditText(this@BuilderActivity).apply {
                setText(init)
                this.hint = hint
                setSingleLine(true)
                if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
            }
            box.addView(e)
            return e
        }

        fun choice(options: List<String>, selected: String): Spinner {
            val s = Spinner(this@BuilderActivity)
            s.adapter = ArrayAdapter(this@BuilderActivity, android.R.layout.simple_spinner_dropdown_item, options)
            val i = options.indexOf(selected)
            if (i >= 0) s.setSelection(i)
            box.addView(s)
            return s
        }

        fun check(t: String, init: Boolean): CheckBox {
            val c = CheckBox(this@BuilderActivity).apply { text = t; isChecked = init }
            box.addView(c)
            return c
        }

        fun add(v: View) = box.addView(v)

        fun chooser(edit: EditText, label: String, items: () -> List<String>) {
            box.addView(btn(label, true) {
                val l = items()
                if (l.isEmpty()) {
                    toast("Nothing to choose yet")
                } else {
                    AlertDialog.Builder(this@BuilderActivity)
                        .setItems(l.toTypedArray()) { _, i -> edit.setText(l[i]) }
                        .show()
                }
            })
        }

        fun region(r: IntArray): Array<EditText> {
            val names = arrayOf("left", "top", "right", "bottom")
            val edits = Array(4) { i ->
                EditText(this@BuilderActivity).apply {
                    setText(if (r.size == 4) r[i].toString() else "0")
                    hint = names[i]
                    setSingleLine(true)
                    inputType = InputType.TYPE_CLASS_NUMBER
                }
            }
            val row = LinearLayout(this@BuilderActivity).apply { orientation = LinearLayout.HORIZONTAL }
            edits.forEach { row.addView(it, LinearLayout.LayoutParams(0, WRAP, 1f)) }
            box.addView(row)
            return edits
        }
    }

    private fun readRegion(e: Array<EditText>) = IntArray(4) { e[it].int(0) }

    private fun showForm(title: String, f: Form, onOk: () -> Unit, onCancel: () -> Unit = {}): AlertDialog =
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(ScrollView(this).apply { addView(f.box) })
            .setPositiveButton("Save") { _, _ -> onOk(); changed() }
            .setNegativeButton("Cancel") { _, _ -> onCancel() }
            .setOnCancelListener { onCancel() }
            .show()

    private fun testRun(body: (AbstractEngine) -> Unit) {
        val s = svc() ?: return
        toast("Testing in 5 s. Switch to your game now")
        s.runAbstract(5, body)
    }

    /** Lets the person drag a box over the game. Needs the floating overlay (accessibility) service. */
    private fun drawOnScreen(start: IntArray?, apply: (IntArray) -> Unit) {
        val s = svc() ?: return
        toast("Switch to your game. A box appears in 4 s")
        moveTaskToBack(true)
        ui.postDelayed({
            s.pickRegion(start) { r ->
                if (r != null) {
                    apply(r)
                    AbstractStore.save(this, project)
                }
                runCatching {
                    s.startActivity(
                        Intent(s, BuilderActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                    )
                }
            }
        }, 4000)
    }

    // ---------- main screen ----------

    private fun render() {
        root.removeAllViews()
        root.addView(tv("Abstract Builder", 26f, true))
        root.addView(
            tv(
                "Jobs hold rules. A rule says: WHEN these conditions are true, DO these actions. " +
                    "In a Continuous job the TOP rule that matches runs first, so put important checks at the top.",
                13f, color = Color.DKGRAY
            )
        )

        // Start / stop
        val full = { b: Button -> b.also { } }
        val start = full(btn("▶ Start project") {
            svc()?.let { s ->
                toast("Starting in 5 s. Switch to your game")
                s.startAbstract(null, 5)
            }
        })
        val stop = full(btn("■ Stop") { MacroService.instance?.stopAll() })
        val panel = full(btn("Show panel") { svc()?.showPanel() })
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(start, stop, panel).forEach { controls.addView(it, LinearLayout.LayoutParams(0, WRAP, 1f)) }
        root.addView(controls)

        // Live log
        root.addView(section("Live log"))
        val lv = tv(ExecLog.tail(25), 11f, color = Color.rgb(200, 255, 200)).apply {
            typeface = Typeface.MONOSPACE
            setBackgroundColor(Color.rgb(25, 30, 30))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            minLines = 6
        }
        logView = lv
        root.addView(lv, LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(btn("Clear log", true) { ExecLog.clear() })

        // Project
        root.addView(section("Project: ${project.name}"))
        root.addView(
            tv(
                "Main job: ${project.mainJob}   ·   delay between steps: ${project.stepDelayMs} ms",
                13f, color = Color.DKGRAY
            )
        )
        root.addView(btn("Project settings", true) { projectDialog() })

        // Jobs
        root.addView(section("Jobs"))
        val chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (j in project.jobs) {
            val mark = if (j.name == activeJob) "● " else ""
            chips.addView(btn(mark + j.name, true) { activeJob = j.name; render() })
        }
        chips.addView(btn("+ New job", true) { newJobDialog() })
        root.addView(HorizontalScrollView(this).apply { addView(chips) })

        project.job(activeJob)?.let { jobSection(it) }

        // Variables
        root.addView(section("Variables"))
        val vc = card()
        rows(vc, project.vars, { "${it.name} (${it.type}) starts as ${it.initial}" }) { varDialog(it, false) }
        vc.addView(btn("+ Variable", true) {
            val v = VarDef("var${project.vars.size + 1}")
            project.vars.add(v)
            varDialog(v, true)
        })
        root.addView(vc)

        // Custom actions
        root.addView(section("Custom actions"))
        root.addView(tv("A reusable block of actions. Use it from any rule with the 'Custom action' type.", 13f, color = Color.DKGRAY))
        for (ca in project.customs) root.addView(customCard(ca))
        root.addView(btn("+ Custom action", true) {
            val ca = CustomAction("Custom${project.customs.size + 1}")
            project.customs.add(ca)
            customDialog(ca, true)
        })
    }

    private fun jobSection(job: Job) {
        val c = card()
        c.addView(
            tv(
                "Active job: ${job.name}${if (job.name == project.mainJob) "  (main)" else ""}",
                16f, true
            )
        )
        val scope = if (job.scope == Scope.ONCE) "Once: one pass, runs every rule whose conditions are true" else
            "Continuous: repeats, only the first matching rule runs each pass"
        c.addView(tv(scope, 13f, color = Color.DKGRAY))
        val wd = if (job.failLimit > 0)
            "Watchdog: after ${job.failLimit} failed passes in a row, run '${job.recoveryJob.ifBlank { "(no recovery job)" }}' and restart"
        else "Watchdog: off"
        c.addView(tv(wd, 13f, color = Color.DKGRAY))
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(btn("Job settings", true) { jobDialog(job) })
        bar.addView(btn("▶ Run this job", true) {
            svc()?.let { s ->
                toast("Starting in 5 s. Switch to your game")
                s.startAbstract(job.name, 5)
            }
        })
        bar.addView(btn("Delete", true) {
            if (project.jobs.size <= 1) {
                toast("You need at least one job")
            } else {
                AlertDialog.Builder(this).setMessage("Delete job '${job.name}'?")
                    .setPositiveButton("Delete") { _, _ ->
                        project.jobs.remove(job)
                        if (project.mainJob == job.name) project.mainJob = project.jobs[0].name
                        activeJob = project.mainJob
                        changed()
                    }.setNegativeButton("Cancel", null).show()
            }
        })
        c.addView(HorizontalScrollView(this).apply { addView(bar) })
        root.addView(c)

        root.addView(tv("Rules (top = highest priority)", 15f, true).apply { setPadding(0, dp(12), 0, 0) })
        for (i in job.rules.indices) root.addView(ruleCard(job, i))
        root.addView(btn("+ Rule", true) {
            val r = Rule("Rule ${job.rules.size + 1}")
            job.rules.add(r)
            ruleDialog(r, job, true)
        })
    }

    private fun ruleCard(job: Job, idx: Int): View {
        val r = job.rules[idx]
        val c = card()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(
            tv("${idx + 1}. ${r.name}${if (r.enabled) "" else "  (off)"}", 15f, true),
            LinearLayout.LayoutParams(0, WRAP, 1f)
        )
        head.addView(btn("↑", true) { if (idx > 0) { Collections.swap(job.rules, idx, idx - 1); changed() } })
        head.addView(btn("↓", true) { if (idx < job.rules.size - 1) { Collections.swap(job.rules, idx, idx + 1); changed() } })
        head.addView(btn("✎", true) { ruleDialog(r, job, false) })
        head.addView(btn("✕", true) { job.rules.removeAt(idx); changed() })
        c.addView(head)

        c.addView(tv("WHEN  ${AbstractStore.describe(r.cond)}", 13f, true, Color.rgb(20, 90, 120)))
        c.addView(btn("Match: ${if (r.cond.mode == "OR") "ANY (OR)" else "ALL (AND)"}  ·  tap to switch", true) {
            r.cond.mode = if (r.cond.mode == "OR") "AND" else "OR"
            changed()
        })
        rows(c, r.cond.conds, { AbstractStore.describe(it) }) { condDialog(it, false, r.cond.conds) }
        c.addView(
            hrow(
                btn("+ Condition", true) {
                    val n = Cond()
                    r.cond.conds.add(n)
                    condDialog(n, true, r.cond.conds)
                },
                btn("Test conditions", true) { testRun { e -> e.evalGroup(r.cond, true) } }
            )
        )

        c.addView(tv("DO", 13f, true, Color.rgb(20, 110, 50)).apply { setPadding(0, dp(8), 0, 0) })
        rows(c, r.actions, { AbstractStore.describe(it) }) { actionDialog(it, false, r.actions) }
        c.addView(
            hrow(
                btn("+ Action", true) {
                    val n = Action()
                    r.actions.add(n)
                    actionDialog(n, true, r.actions)
                },
                btn("Test actions", true) { testRun { e -> e.runActions(r.actions) } }
            )
        )
        return c
    }

    private fun customCard(ca: CustomAction): View {
        val c = card()
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(tv(ca.name, 15f, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(btn("✎", true) { customDialog(ca, false) })
        head.addView(btn("✕", true) { project.customs.remove(ca); changed() })
        c.addView(head)
        rows(c, ca.actions, { AbstractStore.describe(it) }) { actionDialog(it, false, ca.actions) }
        c.addView(
            hrow(
                btn("+ Action", true) {
                    val n = Action()
                    ca.actions.add(n)
                    actionDialog(n, true, ca.actions)
                },
                btn("Test", true) { testRun { e -> e.runActions(ca.actions) } }
            )
        )
        return c
    }

    // ---------- dialogs ----------

    private fun projectDialog() {
        val f = Form()
        f.label("Project name")
        val name = f.text(project.name)
        f.label("Delay between steps (ms)")
        val delay = f.text(project.stepDelayMs.toString(), "150", true)
        f.label("Main job (what Start runs)")
        val main = f.choice(project.jobs.map { it.name }, project.mainJob)
        showForm("Project settings", f, {
            project.name = name.text.toString().ifBlank { "My macro" }
            project.stepDelayMs = delay.int(150).coerceIn(0, 10000)
            project.mainJob = (main.selectedItem as? String) ?: project.mainJob
        })
    }

    private fun newJobDialog() {
        val f = Form()
        f.label("Job name")
        val name = f.text("Job${project.jobs.size + 1}")
        showForm("New job", f, {
            var n = name.text.toString().trim().ifBlank { "Job${project.jobs.size + 1}" }
            while (project.job(n) != null) n += "2"
            project.jobs.add(Job(n))
            activeJob = n
        })
    }

    private fun jobDialog(job: Job) {
        val f = Form()
        f.label("Job name")
        val name = f.text(job.name)
        f.label("When does it run?")
        val once = "Once per start (one pass)"
        val cont = "Continuous (repeat)"
        val scope = f.choice(listOf(once, cont), if (job.scope == Scope.ONCE) once else cont)
        f.label("Pause between passes (ms, continuous jobs)")
        val loop = f.text(job.loopDelayMs.toString(), "300", true)
        f.label("Watchdog: failed passes in a row before recovery (0 = off)")
        val fail = f.text(job.failLimit.toString(), "0", true)
        f.label("Recovery job to run, then restart this job")
        val rec = f.text(job.recoveryJob, "job name")
        f.chooser(rec, "Choose job") { project.jobs.map { it.name }.filter { it != job.name } }
        val reset = f.check("Reset variables when restarting", job.resetVarsOnRestart)
        showForm("Job settings", f, {
            val old = job.name
            val n = name.text.toString().trim()
            if (n.isNotEmpty() && n != old && project.job(n) == null) {
                job.name = n
                if (project.mainJob == old) project.mainJob = n
                for (j in project.jobs) {
                    if (j.recoveryJob == old) j.recoveryJob = n
                    for (r in j.rules) for (a in r.actions) if (a.type == "runjob" && a.name == old) a.name = n
                }
                for (ca in project.customs) for (a in ca.actions) if (a.type == "runjob" && a.name == old) a.name = n
                activeJob = n
            }
            job.scope = if (scope.selectedItem == cont) Scope.CONTINUOUS else Scope.ONCE
            job.loopDelayMs = loop.int(300).coerceIn(0, 60000)
            job.failLimit = fail.int(0).coerceIn(0, 1000)
            job.recoveryJob = rec.text.toString().trim()
            job.resetVarsOnRestart = reset.isChecked
        })
    }

    private fun ruleDialog(r: Rule, job: Job, isNew: Boolean) {
        val f = Form()
        f.label("Rule name")
        val name = f.text(r.name)
        val on = f.check("Enabled", r.enabled)
        showForm("Rule", f, {
            r.name = name.text.toString().ifBlank { r.name }
            r.enabled = on.isChecked
        }, { if (isNew) { job.rules.remove(r); changed() } })
    }

    private fun customDialog(ca: CustomAction, isNew: Boolean) {
        val f = Form()
        f.label("Custom action name")
        val name = f.text(ca.name)
        showForm("Custom action", f, {
            val old = ca.name
            val n = name.text.toString().trim()
            if (n.isNotEmpty() && n != old && project.custom(n) == null) {
                ca.name = n
                for (j in project.jobs) for (r in j.rules) for (a in r.actions) if (a.type == "custom" && a.name == old) a.name = n
                for (o in project.customs) for (a in o.actions) if (a.type == "custom" && a.name == old) a.name = n
            }
        }, { if (isNew) { project.customs.remove(ca); changed() } })
    }

    private fun varDialog(v: VarDef, isNew: Boolean) {
        val f = Form()
        f.label("Variable name")
        val name = f.text(v.name)
        f.label("Type")
        val type = f.choice(listOf("bool", "number", "string"), v.type)
        f.label("Starting value (true/false, a number, or text)")
        val init = f.text(v.initial)
        showForm("Variable", f, {
            v.name = name.text.toString().trim().ifBlank { v.name }
            v.type = (type.selectedItem as? String) ?: "number"
            v.initial = init.text.toString()
        }, { if (isNew) { project.vars.remove(v); changed() } })
    }

    private fun copyCond(to: Cond, n: Cond) {
        to.type = n.type; to.image = n.image; to.appears = n.appears; to.region = n.region
        to.similarity = n.similarity; to.varName = n.varName; to.cmp = n.cmp; to.value = n.value
    }

    private fun condDialog(c: Cond, isNew: Boolean, owner: MutableList<Cond>) {
        val f = Form()
        f.label("Type")
        val type = f.choice(listOf("Image", "Variable"), if (c.type == "var") "Variable" else "Image")

        f.label("IMAGE — picture name (save pictures with ▭ Crop on the floating panel)")
        val img = f.text(c.image, "picture name")
        f.chooser(img, "Choose saved picture") { Images.names(this) }
        f.label("Image should…")
        val app = f.choice(listOf("Appear", "NOT appear"), if (c.appears) "Appear" else "NOT appear")
        f.label("Where to look: left, top, right, bottom (all 0 = whole screen)")
        val reg = f.region(c.region)
        f.label("Similarity % (50–100, higher = stricter)")
        val sim = f.text(c.similarity.toString(), "85", true)

        f.label("VARIABLE — name")
        val vn = f.text(c.varName, "variable name")
        f.chooser(vn, "Choose variable") { project.vars.map { it.name } }
        f.label("is")
        val cmp = f.choice(listOf("==", "!=", ">", "<", ">=", "<="), c.cmp)
        f.label("this value")
        val value = f.text(c.value, "true / 5 / text")

        fun read() = Cond(
            type = if (type.selectedItem == "Variable") "var" else "image",
            image = img.text.toString().trim(),
            appears = app.selectedItem == "Appear",
            region = readRegion(reg),
            similarity = sim.int(85).coerceIn(50, 100),
            varName = vn.text.toString().trim(),
            cmp = (cmp.selectedItem as? String) ?: "==",
            value = value.text.toString().trim()
        )

        var dlg: AlertDialog? = null
        f.add(btn("Draw the area on screen", true) {
            copyCond(c, read())
            dlg?.dismiss()
            AbstractStore.save(this, project)
            drawOnScreen(c.region) { r -> c.region = r }
        })
        f.add(btn("Test this condition", true) {
            val t = read()
            testRun { e -> e.evalCond(t, true) }
        })
        dlg = showForm("Condition", f, { copyCond(c, read()) }, { if (isNew) { owner.remove(c); changed() } })
    }

    private val typeLabels = listOf("Tap", "Tap image", "Swipe", "Joystick drag", "Wait", "Set variable", "Run job", "Custom action")

    private fun labelOf(a: Action) = when (a.type) {
        "tapimg" -> "Tap image"
        "swipe" -> if (a.style == "drag") "Joystick drag" else "Swipe"
        "wait" -> "Wait"
        "setvar" -> "Set variable"
        "runjob" -> "Run job"
        "custom" -> "Custom action"
        else -> "Tap"
    }

    private fun copyAction(to: Action, n: Action) {
        to.type = n.type; to.x1 = n.x1; to.y1 = n.y1; to.x2 = n.x2; to.y2 = n.y2; to.ms = n.ms
        to.style = n.style; to.image = n.image; to.region = n.region; to.similarity = n.similarity
        to.name = n.name; to.op = n.op; to.value = n.value
    }

    private fun actionDialog(a: Action, isNew: Boolean, owner: MutableList<Action>) {
        val f = Form()
        f.label("Action type")
        val type = f.choice(typeLabels, labelOf(a))

        f.label("TAP / SWIPE — start (or tap) x, y")
        val x1 = f.text(a.x1.toString(), "x", true)
        val y1 = f.text(a.y1.toString(), "y", true)
        f.label("SWIPE / DRAG — end x, y")
        val x2 = f.text(a.x2.toString(), "x", true)
        val y2 = f.text(a.y2.toString(), "y", true)
        f.label("Milliseconds (wait length, or swipe/drag duration)")
        val ms = f.text(a.ms.toString(), "500", true)

        f.label("TAP IMAGE — picture name")
        val img = f.text(a.image, "picture name")
        f.chooser(img, "Choose saved picture") { Images.names(this) }
        f.label("Where to look: left, top, right, bottom (all 0 = whole screen)")
        val reg = f.region(a.region)
        f.label("Similarity %")
        val sim = f.text(a.similarity.toString(), "85", true)

        f.label("SET VARIABLE / RUN JOB / CUSTOM — name")
        val nm = f.text(a.name, "name")
        f.chooser(nm, "Choose…") {
            when (type.selectedItem) {
                "Run job" -> project.jobs.map { it.name }
                "Custom action" -> project.customs.map { it.name }
                else -> project.vars.map { it.name }
            }
        }
        f.label("Variable operation")
        val op = f.choice(listOf("set", "add", "toggle"), a.op)
        f.label("Value (for set / add)")
        val value = f.text(a.value, "true / 5 / text")

        fun read(): Action {
            val sel = type.selectedItem as? String ?: "Tap"
            return Action(
                type = when (sel) {
                    "Tap image" -> "tapimg"
                    "Swipe", "Joystick drag" -> "swipe"
                    "Wait" -> "wait"
                    "Set variable" -> "setvar"
                    "Run job" -> "runjob"
                    "Custom action" -> "custom"
                    else -> "tap"
                },
                x1 = x1.int(0), y1 = y1.int(0), x2 = x2.int(0), y2 = y2.int(0),
                ms = ms.int(500).coerceIn(1, 600000),
                style = if (sel == "Joystick drag") "drag" else "swipe",
                image = img.text.toString().trim(),
                region = readRegion(reg),
                similarity = sim.int(85).coerceIn(50, 100),
                name = nm.text.toString().trim(),
                op = (op.selectedItem as? String) ?: "set",
                value = value.text.toString().trim()
            )
        }

        var dlg: AlertDialog? = null
        f.add(btn("Place on screen (drag a box)", true) {
            val cur = read()
            copyAction(a, cur)
            dlg?.dismiss()
            AbstractStore.save(this, project)
            val swipe = a.type == "swipe"
            val start = if (swipe) intArrayOf(minOf(a.x1, a.x2), minOf(a.y1, a.y2), maxOf(a.x1, a.x2), maxOf(a.y1, a.y2))
            else intArrayOf(maxOf(0, a.x1 - 80), maxOf(0, a.y1 - 80), a.x1 + 80, a.y1 + 80)
            drawOnScreen(start) { r ->
                if (swipe) {
                    a.x1 = r[0]; a.y1 = r[1]; a.x2 = r[2]; a.y2 = r[3]
                } else if (a.type == "tapimg") {
                    a.region = r
                } else {
                    a.x1 = (r[0] + r[2]) / 2; a.y1 = (r[1] + r[3]) / 2
                }
            }
        })
        f.add(tv("Tap: the box centre is tapped. Swipe: from the box's top-left to its bottom-right. Tap image: the box is the search area.", 12f, color = Color.GRAY))
        f.add(btn("Test this action", true) {
            val t = read()
            testRun { e -> e.exec(t) }
        })
        dlg = showForm("Action", f, { copyAction(a, read()) }, { if (isNew) { owner.remove(a); changed() } })
    }
}
