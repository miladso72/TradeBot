package com.lbank.btdemobot

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSocket client that stays connected to the PC signal server and
 * hands every incoming signal to [onSignal]. Auto-reconnects with backoff.
 */
class SignalClient(
    private val url: String,
    private val token: String,
    private val onSignal: (Signal) -> Unit,
    private val onState: (connected: Boolean, text: String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var closing = false
    private var attempt = 0

    fun start() {
        closing = false
        connect()
    }

    private fun fullUrl(): String {
        if (token.isEmpty()) return url
        val sep = if (url.contains("?")) "&" else "?"
        return "$url$sep" + "token=" + token
    }

    private fun connect() {
        if (closing) return
        val req = try {
            Request.Builder().url(fullUrl()).build()
        } catch (e: Exception) {
            LogBus.log("URL نامعتبر: ${e.message}")
            onState(false, "URL نامعتبر")
            return
        }
        LogBus.log("اتصال به $url ...")
        ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempt = 0
                LogBus.log("وصل شد ✓")
                onState(true, "متصل")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val o = JSONObject(text)
                    when (o.optString("type")) {
                        "signal" -> Signal.fromJson(o)?.let { onSignal(it) }
                        "welcome" -> LogBus.log("خوش‌آمد از سرور")
                        "pong" -> {}
                        "unsafe" -> LogBus.log("ناامن: ${o.optString("symbol")}")
                        else -> {}
                    }
                }.onFailure { LogBus.log("پیام ناخوانا: ${it.message}") }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                LogBus.log("اتصال بسته شد ($code)")
                onState(false, "بسته شد")
                scheduleReconnect()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                LogBus.log("قطع: ${t.message}")
                onState(false, "قطع: ${t.message}")
                scheduleReconnect()
            }
        })
    }

    private fun scheduleReconnect() {
        if (closing) return
        attempt++
        val delay = minOf(15000L, 500L * attempt)
        Thread {
            runCatching { Thread.sleep(delay) }
            connect()
        }.apply { isDaemon = true }.start()
    }

    fun stop() {
        closing = true
        runCatching { ws?.close(1000, "bye") }
        ws = null
        onState(false, "قطع")
    }
}
