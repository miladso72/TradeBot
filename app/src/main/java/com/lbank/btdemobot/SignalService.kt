package com.lbank.btdemobot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground service that keeps the signal feed alive and hands every signal
 * to the accessibility automation engine.
 *
 * Two feeds can be used (see prefs.sourceMode):
 *   • telegram – long poll the signal channel (no PC needed, sub-second)
 *   • ws       – the PC bridge WebSocket (old behaviour)
 *   • both     – whichever fires first wins; TelegramSource has its own
 *                per-symbol cooldown so one signal is not traded twice.
 */
class SignalService : Service() {

    private var ws: SignalClient? = null
    private var tg: TelegramSource? = null
    private lateinit var prefs: Prefs

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSources()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startSources()
        }
        return START_STICKY
    }

    // ------------------------------------------------------------------ feeds
    private fun startSources() {
        prefs = Prefs(this)
        startForegroundCompat(buildNotification("در حال راه‌اندازی…"))
        val mode = prefs.sourceMode.lowercase()
        if (mode == "telegram" || mode == "both") startTelegram()
        if (mode == "ws" || mode == "both") startWs()
        if (mode != "telegram" && mode != "ws" && mode != "both") {
            LogBus.log("منبع سیگنال ناشناخته: ${prefs.sourceMode}")
        }
    }

    private fun startTelegram() {
        if (tg != null) return
        LogBus.log("منبع سیگنال: کانال تلگرام ${prefs.tgChannel}")
        tg = TelegramSource(
            token = prefs.tgToken.trim(),
            channel = prefs.tgChannel.trim(),
            minScore = prefs.minScore,
            cooldownSec = prefs.cooldownSec,
            maxAgeSec = prefs.maxAgeSec,
            onSignal = { s -> dispatch(s) },
            onState = { ok, text ->
                StatusBus.tgConnected = ok
                StatusBus.tgText = text
                refreshNotification()
            }
        )
        tg?.start()
    }

    private fun startWs() {
        if (ws != null) return
        LogBus.log("منبع سیگنال: پل کامپیوتر ${prefs.serverUrl}")
        ws = SignalClient(
            url = prefs.serverUrl,
            token = prefs.token,
            onSignal = { s -> dispatch(s) },
            onState = { ok, text ->
                StatusBus.connected = ok
                StatusBus.text = text
                refreshNotification()
            }
        )
        ws?.start()
    }

    private fun stopSources() {
        tg?.stop(); tg = null
        ws?.stop(); ws = null
        StatusBus.connected = false
        StatusBus.tgConnected = false
        StatusBus.text = "قطع"
        StatusBus.tgText = "قطع"
    }

    override fun onDestroy() {
        stopSources()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --------------------------------------------------------------- dispatch
    /** One entry point for every signal, whatever feed delivered it. */
    private fun dispatch(s: Signal) {
        StatusBus.signals++
        StatusBus.lastSignal = "${s.bitunixSymbol} ${s.direction} @ " +
                SignalFormat.fmt(s.entryPrice)
        val src = if (s.source == "tg") "تلگرام" else "پل"
        LogBus.log("🔔 سیگنال [$src]: ${s.symbol} → ${s.bitunixSymbol} ${s.direction} " +
                "entry=${SignalFormat.fmt(s.entryPrice)} " +
                "tp=${SignalFormat.fmt(s.tpPrice)} sl=${SignalFormat.fmt(s.slPrice)} " +
                "score=${s.score ?: "-"}")
        refreshNotification()
        val a11y = BitunixAccessibilityService.instance
        if (a11y != null) {
            a11y.submitSignal(s)
        } else {
            LogBus.log("سرویس دسترسی‌پذیری روشن نیست — سیگنال اجرا نشد")
        }
    }

    // ------------------------------------------------------------ notification
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL) == null) {
                val ch = NotificationChannel(
                    CHANNEL, "Bitunix Demo Bot",
                    NotificationManager.IMPORTANCE_LOW
                )
                ch.setShowBadge(false)
                mgr.createNotificationChannel(ch)
            }
        }
    }

    private fun stateText(): String {
        val parts = ArrayList<String>()
        parts += if (StatusBus.tgConnected) "تلگرام ✓" else "تلگرام ✗"
        parts += if (StatusBus.connected) "پل ✓" else "پل ✗"
        val base = parts.joinToString("  ")
        val last = StatusBus.lastSignal
        return if (last.isEmpty()) base else "$base  |  $last"
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle("Bitunix Demo Bot")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun startForegroundCompat(n: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, n, type)
        }
    }

    private fun refreshNotification() {
        runCatching {
            val mgr = getSystemService(NotificationManager::class.java)
            mgr.notify(NOTIF_ID, buildNotification(stateText()))
        }
    }

    companion object {
        const val ACTION_STOP = "com.lbank.btdemobot.STOP"
        private const val CHANNEL = "btbot"
        private const val NOTIF_ID = 4101
    }
}
