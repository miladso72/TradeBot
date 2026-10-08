package com.lbank.btdemobot

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.widget.doAfterTextChanged
import com.lbank.btdemobot.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var prefs: Prefs
    private val ui = Handler(Looper.getMainLooper())
    private var lastLogCount = 0

    private val sourceLabels = listOf(
        "تلگرام (مستقیم، بدون کامپیوتر)",
        "پل کامپیوتر (WebSocket)",
        "هر دو"
    )
    private val sourceValues = listOf("telegram", "ws", "both")

    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            refreshLog()
            ui.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        prefs = Prefs(this)

        // seed the plan on first run
        if (prefs.planJson.isBlank()) prefs.planJson = BitunixAccessibilityService.DEFAULT_PLAN

        loadIntoUi()
        wireButtons()
        askNotificationPermission()

        ui.post(ticker)
    }

    override fun onDestroy() {
        ui.removeCallbacks(ticker)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    // ------------------------------------------------------------ ui <-> prefs
    private fun loadIntoUi() {
        b.spSource.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, sourceLabels
        )
        val idx = sourceValues.indexOf(prefs.sourceMode).takeIf { it >= 0 } ?: 0
        b.spSource.setSelection(idx)

        b.etTgToken.setText(prefs.tgToken)
        b.etTgChannel.setText(prefs.tgChannel)
        b.etServer.setText(prefs.serverUrl)
        b.etToken.setText(prefs.token)
        b.etTargetPackage.setText(prefs.targetPackage)
        b.etMargin.setText(SignalFormat.fmt(prefs.marginUsdt))
        b.etLeverage.setText(prefs.leverage.toString())
        b.etOrderType.setText(prefs.orderType)
        b.etMinScore.setText(prefs.minScore.toString())
        b.etCooldown.setText(prefs.cooldownSec.toString())
        b.swIsolated.isChecked = prefs.isolated
        b.swSetTpSl.isChecked = prefs.setTpSl
        b.swEnabled.isChecked = prefs.enabled
        b.swDryRun.isChecked = prefs.dryRun
    }

    private fun wireButtons() {
        b.spSource.onItemSelectedListener = simpleListener { pos ->
            if (pos in sourceValues.indices) prefs.sourceMode = sourceValues[pos]
        }

        b.etTgToken.doAfterTextChanged { prefs.tgToken = it?.toString()?.trim() ?: "" }
        b.etTgChannel.doAfterTextChanged { prefs.tgChannel = it?.toString()?.trim() ?: "" }
        b.etServer.doAfterTextChanged { prefs.serverUrl = it?.toString() ?: "" }
        b.etToken.doAfterTextChanged { prefs.token = it?.toString() ?: "" }
        b.etTargetPackage.doAfterTextChanged { prefs.targetPackage = it?.toString() ?: "" }
        b.etOrderType.doAfterTextChanged {
            val v = it?.toString()?.trim()?.lowercase() ?: ""
            if (v == "limit" || v == "market") prefs.orderType = v
        }
        b.etMargin.doAfterTextChanged {
            it?.toString()?.toDoubleOrNull()?.takeIf { v -> v > 0 }?.let { v -> prefs.marginUsdt = v }
        }
        b.etLeverage.doAfterTextChanged {
            it?.toString()?.toIntOrNull()?.takeIf { v -> v in 1..125 }?.let { v -> prefs.leverage = v }
        }
        b.etMinScore.doAfterTextChanged {
            it?.toString()?.toIntOrNull()?.takeIf { v -> v in 0..10 }?.let { v -> prefs.minScore = v }
        }
        b.etCooldown.doAfterTextChanged {
            it?.toString()?.toLongOrNull()?.takeIf { v -> v >= 0 }?.let { v -> prefs.cooldownSec = v }
        }

        b.swIsolated.setOnCheckedChangeListener { _, v -> prefs.isolated = v }
        b.swSetTpSl.setOnCheckedChangeListener { _, v -> prefs.setTpSl = v }
        b.swEnabled.setOnCheckedChangeListener { _, v -> prefs.enabled = v }
        b.swDryRun.setOnCheckedChangeListener { _, v -> prefs.dryRun = v }

        b.btnA11y.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            LogBus.log("در لیست، «Bitunix Demo Auto-Trader» را روشن کن")
        }

        b.btnStart.setOnClickListener {
            val i = Intent(this, SignalService::class.java)
            ContextCompat.startForegroundService(this, i)
            LogBus.log("شروع شنود (منبع: ${prefs.sourceMode})")
        }

        b.btnStop.setOnClickListener {
            stopService(Intent(this, SignalService::class.java))
            LogBus.log("شنود قطع شد")
        }

        b.btnDump.setOnClickListener {
            val a = BitunixAccessibilityService.instance
            if (a == null) LogBus.log("اول سرویس دسترسی‌پذیری را روشن کن")
            else {
                LogBus.log("در حال گرفتن ساختار صفحه...")
                a.dumpNow()
            }
        }

        b.btnPasteTest.setOnClickListener { showPasteTestDialog() }

        b.btnTest.setOnClickListener {
            val sig = Signal(
                id = "TEST",
                symbol = "TEST_USDT",
                bitunixSymbol = "TESTUSDT",
                direction = "LONG",
                entryPrice = 1.0,
                tpPrice = 1.02,
                slPrice = 0.99,
                score = 10
            )
            val a = BitunixAccessibilityService.instance
            if (a == null) LogBus.log("سرویس دسترسی‌پذیری روشن نیست")
            else {
                LogBus.log("سیگنال تستِ محلی ارسال شد")
                a.submitSignal(sig)
            }
        }

        b.btnPlan.setOnClickListener { showPlanDialog() }
        b.btnClear.setOnClickListener { LogBus.clear() }
    }

    private fun simpleListener(onPick: (Int) -> Unit) =
        object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?, view: android.view.View?,
                position: Int, id: Long
            ) = onPick(position)

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

    /**
     * Calibration helper: paste a real message from the signal channel and see
     * exactly what the phone extracts (and run the plan on it).
     */
    private fun showPasteTestDialog() {
        val edit = EditText(this)
        edit.hint = "متن کامل پیام کانال را اینجا بچسبان"
        edit.textSize = 11f
        edit.setPadding(30, 30, 30, 30)
        edit.minLines = 6
        AlertDialog.Builder(this)
            .setTitle("اجرای آزمایشی روی یک پیام کانال")
            .setView(edit)
            .setPositiveButton("تحلیل و اجرا") { _, _ ->
                val text = edit.text.toString()
                val sig = SignalParser.fromChannelMessage(text)
                if (sig == null) {
                    LogBus.log("این متن سیگنال معتبری نیست (یا «ناامن» است)")
                    return@setPositiveButton
                }
                LogBus.log("تحلیل پیام: ${sig.symbol} → ${sig.bitunixSymbol} " +
                        "entry=${SignalFormat.fmt(sig.entryPrice)} " +
                        "tp=${SignalFormat.fmt(sig.tpPrice)} sl=${SignalFormat.fmt(sig.slPrice)} " +
                        "score=${sig.score ?: "-"}")
                LogBus.log("مقدار محاسبه‌شده: qty=" +
                        SignalFormat.qty(sig, prefs.tradeCfg()) +
                        " (${SignalFormat.fmt(prefs.marginUsdt)}$ × ${prefs.leverage}x)")
                val a = BitunixAccessibilityService.instance
                if (a == null) LogBus.log("سرویس دسترسی‌پذیری روشن نیست")
                else a.submitSignal(sig)
            }
            .setNegativeButton("انصراف", null)
            .show()
    }

    private fun showPlanDialog() {
        val edit = EditText(this)
        edit.setText(prefs.planJson)
        edit.textSize = 11f
        edit.setPadding(30, 30, 30, 30)
        AlertDialog.Builder(this)
            .setTitle("دستور معاملات (action plan) — JSON")
            .setView(edit)
            .setPositiveButton("ذخیره") { _, _ ->
                val txt = edit.text.toString()
                try {
                    ActionPlan.fromJson(txt)
                    prefs.planJson = txt
                    LogBus.log("پلن ذخیره شد (معتبر)")
                    Toast.makeText(this, "پلن ذخیره شد", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    LogBus.log("پلن نامعتبر: ${e.message} — ذخیره نشد")
                    Toast.makeText(this, "JSON نامعتبر است", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("بازگردانی پیش‌فرض") { _, _ ->
                prefs.planJson = BitunixAccessibilityService.DEFAULT_PLAN
                LogBus.log("پلن به پیش‌فرض برگشت")
            }
            .setNeutralButton("انصراف", null)
            .show()
    }

    // ------------------------------------------------------------ status/log
    private fun refreshStatus() {
        val tg = StatusBus.tgConnected
        b.tvTgStatus.text =
            if (tg) "کانال تلگرام: متصل" else "کانال تلگرام: قطع (${StatusBus.tgText})"
        b.tvTgStatus.setTextColor(
            ContextCompat.getColor(this, if (tg) R.color.ok else R.color.danger)
        )

        val ws = StatusBus.connected
        b.tvConnStatus.text = if (ws) "پل کامپیوتر: متصل" else "پل کامپیوتر: قطع"
        b.tvConnStatus.setTextColor(
            ContextCompat.getColor(this, if (ws) R.color.ok else R.color.text_dim)
        )

        val a11y = BitunixAccessibilityService.isRunning()
        b.tvA11yStatus.text =
            if (a11y) "سرویس دسترسی‌پذیری: روشن" else "سرویس دسترسی‌پذیری: خاموش"
        b.tvA11yStatus.setTextColor(
            ContextCompat.getColor(this, if (a11y) R.color.ok else R.color.danger)
        )
        b.tvSignalCount.text =
            "سیگنال دریافت‌شده: ${StatusBus.signals} | اجرا شده: ${StatusBus.executed}"
        b.tvLastSignal.text =
            "آخرین سیگنال: " + StatusBus.lastSignal.ifEmpty { "—" }
    }

    private fun refreshLog() {
        val snap = LogBus.snapshot()
        if (snap.size == lastLogCount && !(snap.isEmpty() && lastLogCount != 0)) return
        lastLogCount = snap.size
        b.tvLog.text = snap.takeLast(200).joinToString("\n")
    }

    // ------------------------------------------------------------ permissions
    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 71
                )
            }
        }
    }
}
