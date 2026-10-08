package com.lbank.btdemobot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.concurrent.Executors

/**
 * The automation engine. It receives signals (from the foreground service)
 * and plays a configurable [ActionPlan] against the Bitunix app using the
 * Accessibility API — finding views and tapping / typing.
 *
 * Everything runs on a single background worker, so orders never overlap.
 */
class BitunixAccessibilityService : AccessibilityService() {

    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "bt-a11y-worker").apply { isDaemon = true }
    }
    private lateinit var prefs: Prefs

    @Volatile private var lastForegroundPkg: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        prefs = Prefs(this)
        instance = this
        try {
            val si = serviceInfo ?: AccessibilityServiceInfo()
            si.flags = si.flags or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            serviceInfo = si
        } catch (_: Exception) {
        }
        LogBus.log("سرویس دسترسی‌پذیری وصل شد ✓")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            e.packageName?.let { lastForegroundPkg = it.toString() }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        LogBus.log("سرویس دسترسی‌پذیری قطع شد")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        worker.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- entry
    /** Called by the foreground service for every received signal. */
    fun submitSignal(signal: Signal) {
        worker.execute { runPlanFor(signal) }
    }

    // ---------------------------------------------------------------- plan
    private fun runPlanFor(signal: Signal) {
        prefs = Prefs(this)
        if (!prefs.enabled) {
            LogBus.log("سیگنال ${signal.symbol} رسید ولی «اجرای خودکار» خاموش است")
            return
        }
        val plan = try {
            ActionPlan.fromJson(prefs.planJson.ifBlank { DEFAULT_PLAN })
        } catch (e: Exception) {
            LogBus.log("پلن خراب است (${e.message}) → پلن پیش‌فرض")
            ActionPlan.fromJson(DEFAULT_PLAN)
        }
        val cfg = prefs.tradeCfg()
        LogBus.log("▶ اجرای پلن '${plan.name}' برای ${signal.bitunixSymbol} " +
                "(${signal.direction}) | ${cfg.marginUsdt}$ × ${cfg.leverage}x " +
                "${cfg.orderType} | qty=" + SignalFormat.qty(signal, cfg) +
                " | ${plan.steps.size} مرحله" +
                if (prefs.dryRun) "  [حالت آزمایشی]" else "")
        val t0 = System.currentTimeMillis()
        for ((idx, step) in plan.steps.withIndex()) {
            val tag = "[${idx + 1}/${plan.steps.size}] ${step.action}"
            val ok = try {
                runStep(step, signal, cfg)
            } catch (e: Exception) {
                LogBus.log("$tag → خطا: ${e.message}")
                false
            }
            when {
                ok -> LogBus.log("$tag → OK")
                step.optional -> LogBus.log("$tag → رد شد (optional) — ادامه")
                else -> {
                    LogBus.log("$tag → NO  ✖ پلن متوقف شد")
                    // remaining steps cannot be trusted once a required one fails
                    break
                }
            }
            if (step.delayMs > 0) sleep(step.delayMs)
        }
        LogBus.log("■ پایان پلن در ${System.currentTimeMillis() - t0} ms")
        StatusBus.executed++
    }

    private fun runStep(step: Step, signal: Signal, cfg: TradeCfg): Boolean {
        val by = step.by
        val value = SignalFormat.subst(step.value, signal, cfg)
        val text = SignalFormat.subst(step.text, signal, cfg)
        val dry = prefs.dryRun
        return when (step.action) {
            "launch" -> {
                val pkg = step.pkg.ifBlank { prefs.targetPackage }
                if (dry) { LogBus.log("   (dry) launch $pkg"); true }
                else launchApp(pkg)
            }
            "sleep" -> true
            "back" -> { if (!dry) performGlobalAction(GLOBAL_ACTION_BACK); true }
            "home" -> { if (!dry) performGlobalAction(GLOBAL_ACTION_HOME); true }
            "wait" -> waitForNode(by, value, step.timeoutMs) != null
            "click" -> {
                val n = waitForNode(by, value, step.timeoutMs)
                if (n == null) { LogBus.log("   پیدا نشد: $by=$value"); false }
                else if (dry) { LogBus.log("   (dry) click $by=$value"); true }
                else tap(n)
            }
            "setText" -> {
                val n = waitForNode(by, value, step.timeoutMs)
                if (n == null) { LogBus.log("   پیدا نشد: $by=$value"); false }
                else if (dry) { LogBus.log("   (dry) setText \"$text\" در $by=$value"); true }
                else setNodeText(n, text)
            }
            "clearText" -> {
                val n = waitForNode(by, value, step.timeoutMs)
                if (n == null) { LogBus.log("   پیدا نشد: $by=$value"); false }
                else if (dry) { LogBus.log("   (dry) clearText $by=$value"); true }
                else setNodeText(n, "")
            }
            "tapPct" -> {
                val p = parseFloats(value, 2)
                if (p == null) { LogBus.log("   مقدار نامعتبر برای tapPct: $value"); false }
                else if (dry) { LogBus.log("   (dry) tapPct $value"); true }
                else tapXY(p[0] * screenWidth(), p[1] * screenHeight())
            }
            "swipe" -> {
                val p = parseFloats(value, 4)
                if (p == null) { LogBus.log("   مقدار نامعتبر برای swipe: $value"); false }
                else if (dry) { LogBus.log("   (dry) swipe $value"); true }
                else swipePct(p[0], p[1], p[2], p[3], step.durationMs)
            }
            else -> { LogBus.log("   action ناشناخته: ${step.action}"); false }
        }
    }

    // ---------------------------------------------------------------- helpers
    private fun parseFloats(s: String, n: Int): FloatArray? {
        val parts = s.split(",").map { it.trim().toFloatOrNull() }
        if (parts.size != n || parts.any { it == null }) return null
        return FloatArray(n) { parts[it]!! }
    }

    private fun screenWidth(): Float = resources.displayMetrics.widthPixels.toFloat()
    private fun screenHeight(): Float = resources.displayMetrics.heightPixels.toFloat()

    // ---------------------------------------------------------------- nodes
    private fun roots(): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in windows) w.root?.let { out.add(it) }
        } catch (_: Exception) {
        }
        if (out.isEmpty()) rootInActiveWindow?.let { out.add(it) }
        return out
    }

    private fun matches(node: AccessibilityNodeInfo, by: String, value: String): Boolean {
        val t = node.text?.toString() ?: ""
        val d = node.contentDescription?.toString() ?: ""
        val id = node.viewIdResourceName ?: ""
        val cls = node.className?.toString() ?: ""
        return when (by) {
            "text" -> t.equals(value, true)
            "text_contains" -> t.contains(value, true)
            "id" -> id == value || id.endsWith(":id/$value")
            "id_contains" -> id.contains(value, true)
            "desc" -> d.equals(value, true)
            "desc_contains" -> d.contains(value, true)
            "class_contains" -> cls.contains(value, true)
            else -> t.equals(value, true)
        }
    }

    private fun collect(
        node: AccessibilityNodeInfo?,
        by: String,
        value: String,
        out: MutableList<AccessibilityNodeInfo>
    ) {
        if (node == null) return
        if (value.isNotEmpty() && matches(node, by, value)) out.add(node)
        for (i in 0 until node.childCount) collect(node.getChild(i), by, value, out)
    }

    private fun findAll(by: String, value: String): List<AccessibilityNodeInfo> {
        val res = ArrayList<AccessibilityNodeInfo>()
        for (r in roots()) collect(r, by, value, res)
        return res
    }

    private fun waitForNode(by: String, value: String, timeoutMs: Long): AccessibilityNodeInfo? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val n = findAll(by, value).firstOrNull()
            if (n != null) return n
            sleep(120)
        }
        return null
    }

    private fun clickableAncestor(n: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return n
    }

    private fun tap(n: AccessibilityNodeInfo): Boolean {
        val c = clickableAncestor(n)
        if (c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        val r = Rect()
        c.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) return false
        return tapXY(r.centerX().toFloat(), r.centerY().toFloat())
    }

    private fun tapXY(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 60)
        val g = GestureDescription.Builder().addStroke(stroke).build()
        return runCatching { dispatchGesture(g, null, null) }.getOrDefault(false)
    }

    private fun swipePct(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        val path = Path().apply {
            moveTo(x1 * screenWidth(), y1 * screenHeight())
            lineTo(x2 * screenWidth(), y2 * screenHeight())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, maxOf(60L, ms))
        val g = GestureDescription.Builder().addStroke(stroke).build()
        return runCatching { dispatchGesture(g, null, null) }.getOrDefault(false)
    }

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) {
            findEditable(n.getChild(i))?.let { return it }
        }
        return null
    }

    private fun setNodeText(n: AccessibilityNodeInfo, text: String): Boolean {
        val target = if (n.isEditable) n else (findEditable(n) ?: n)
        runCatching { target.performAction(AccessibilityNodeInfo.ACTION_FOCUS) }
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
            )
        }
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun launchApp(pkg: String): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg)
        if (i == null) {
            LogBus.log("   اپ «$pkg» نصب/پیدا نشد")
            return false
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { startActivity(i); true }.getOrDefault(false)
    }

    private fun sleep(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }

    // ---------------------------------------------------------------- dump
    /** Write the current on-screen view tree to a file for calibration. */
    fun dumpNow() {
        worker.execute {
            val rs = roots()
            if (rs.isEmpty()) {
                LogBus.log("درخت صفحه خالی است — اول اپ بیت‌یونیکس را باز کن")
                return@execute
            }
            val sb = StringBuilder()
            sb.append("UI dump @ ").append(System.currentTimeMillis()).append('\n')
            sb.append("foreground pkg: ").append(lastForegroundPkg).append("\n\n")
            for (r in rs) dumpNode(r, 0, sb)
            val f = File(getExternalFilesDir(null), "ui_dump_${System.currentTimeMillis()}.txt")
            try {
                f.writeText(sb.toString())
                LogBus.log("ساختار صفحه ذخیره شد:\n${f.absolutePath}")
            } catch (e: Exception) {
                LogBus.log("خطای ذخیره‌ی dump: ${e.message}")
            }
        }
    }

    private fun dumpNode(n: AccessibilityNodeInfo?, depth: Int, sb: StringBuilder) {
        if (n == null) return
        val pad = "  ".repeat(depth)
        val r = Rect(); n.getBoundsInScreen(r)
        sb.append(pad).append(n.className?.toString()?.substringAfterLast('.') ?: "?")
        n.viewIdResourceName?.let { sb.append(" id=").append(it) }
        n.text?.toString()?.replace("\n", " ")?.takeIf { it.isNotEmpty() }
            ?.let { sb.append(" text=\"").append(it).append('"') }
        n.contentDescription?.toString()?.takeIf { it.isNotEmpty() }
            ?.let { sb.append(" desc=\"").append(it).append('"') }
        sb.append(" bounds=").append(r.toShortString())
        if (n.isClickable) sb.append(" clickable")
        if (n.isEditable) sb.append(" editable")
        sb.append('\n')
        if (depth < 40) {
            for (i in 0 until n.childCount) dumpNode(n.getChild(i), depth + 1, sb)
        }
    }

    companion object {
        @Volatile var instance: BitunixAccessibilityService? = null
        fun isRunning(): Boolean = instance != null

        /** Built-in plan; the JSON lives in [Plans] so tests can read it. */
        val DEFAULT_PLAN: String get() = Plans.DEFAULT_PLAN

        @Suppress("unused")
        private val LEGACY_PLAN: String = """
        {
          "name": "bitunix-limit-10x-isolated",
          "steps": [
            {"action":"launch","delay":3000},
            {"action":"wait","by":"text_contains","value":"Futures","timeout":10000,"delay":300,"optional":true},
            {"action":"click","by":"text_contains","value":"Futures","delay":1200,"optional":true},

            {"action":"click","by":"desc_contains","value":"Search","delay":1000,"optional":true},
            {"action":"clearText","by":"class_contains","value":"EditText","timeout":5000,"delay":200},
            {"action":"setText","by":"class_contains","value":"EditText","text":"{BITUNIX_SYMBOL}","delay":1200},
            {"action":"click","by":"text_contains","value":"{BITUNIX_SYMBOL}","delay":2000},

            {"action":"click","by":"text_contains","value":"{LEVERAGE}x","delay":900,"optional":true},
            {"action":"click","by":"desc_contains","value":"Isolated","delay":600,"optional":true},
            {"action":"click","by":"text","value":"Isolated","delay":600,"optional":true},
            {"action":"click","by":"class_contains","value":"EditText","timeout":3000,"delay":300,"optional":true},
            {"action":"clearText","by":"class_contains","value":"EditText","timeout":3000,"delay":200,"optional":true},
            {"action":"setText","by":"class_contains","value":"EditText","text":"{LEVERAGE}","timeout":3000,"delay":400,"optional":true},
            {"action":"click","by":"text","value":"{CONFIRM_LABEL}","delay":900,"optional":true},
            {"action":"back","delay":600,"optional":true},

            {"action":"click","by":"text","value":"{ORDER_TYPE_LABEL}","delay":900},
            {"action":"clearText","by":"text_contains","value":"Price","timeout":5000,"delay":200,"optional":true},
            {"action":"setText","by":"text_contains","value":"Price","text":"{ENTRY_PRICE}","timeout":6000,"delay":600},

            {"action":"clearText","by":"text_contains","value":"Amount","timeout":5000,"delay":200,"optional":true},
            {"action":"setText","by":"text_contains","value":"Amount","text":"{QTY}","timeout":6000,"delay":600},

            {"action":"click","by":"desc_contains","value":"TP/SL","delay":900,"optional":true},
            {"action":"click","by":"text_contains","value":"Take Profit","delay":600,"optional":true},
            {"action":"setText","by":"text_contains","value":"Take Profit","text":"{TP_PRICE}","timeout":4000,"delay":400,"optional":true},
            {"action":"setText","by":"text_contains","value":"Stop Loss","text":"{SL_PRICE}","timeout":4000,"delay":400,"optional":true},
            {"action":"click","by":"text","value":"{CONFIRM_LABEL}","delay":900,"optional":true},

            {"action":"click","by":"text","value":"{SIDE_LABEL}","timeout":6000,"delay":1000},
            {"action":"click","by":"text_contains","value":"{CONFIRM_LABEL}","timeout":8000,"delay":1500,"optional":true}
          ]
        }
        """.trimIndent()
    }
}
