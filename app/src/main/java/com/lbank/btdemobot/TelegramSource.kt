package com.lbank.btdemobot

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Reads the signals straight from the Telegram channel with long polling,
 * so the phone reacts within a fraction of a second of the post — no PC,
 * no WebSocket bridge, no extra server in the middle.
 *
 * Flow:
 *   1. one priming call (offset = -1) to find the newest update, so old
 *      backlog posts are NOT traded when the app starts;
 *   2. then a 30-second long poll on getUpdates; Telegram answers the moment
 *      a new channel_post appears, which is what gives the low latency.
 *
 * Only `channel_post` (new posts) are traded. Edits and the bot's periodic
 * "unsafe" rewrites are ignored by design.
 */
class TelegramSource(
    private val token: String,
    private val channel: String,          // "@TraderProMeBo" or "-1001234567890"
    private val minScore: Int,
    private val cooldownSec: Long,
    private val maxAgeSec: Long = 600L,
    private val onSignal: (Signal) -> Unit,
    private val onState: (connected: Boolean, text: String) -> Unit
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)   // must exceed the poll timeout
        .callTimeout(70, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    @Volatile private var running = false
    private var thread: Thread? = null

    private var offset: Long = 0
    private val seenIds = LinkedHashSet<Long>()
    private val lastFire = HashMap<String, Long>()

    fun start() {
        if (running) return
        if (token.isBlank()) {
            onState(false, "توکن تلگرام خالی است")
            return
        }
        running = true
        thread = Thread({ loop() }, "tg-poll").apply { isDaemon = true }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        onState(false, "قطع")
    }

    // ------------------------------------------------------------------ loop
    private fun loop() {
        try {
            prime()
        } catch (e: Exception) {
            LogBus.log("تلگرام: priming ناموفق (${e.message}) — از ابتدا poll می‌کنیم")
        }
        var attempt = 0
        while (running) {
            try {
                val ok = pollOnce()
                if (ok) {
                    attempt = 0
                } else {
                    attempt++
                    Thread.sleep(minOf(15000L, 1000L * attempt))
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!running) break
                attempt++
                val msg = e.message ?: e.javaClass.simpleName
                LogBus.log("تلگرام: خطای اتصال ($msg)")
                onState(false, "خطا: $msg")
                try {
                    Thread.sleep(minOf(15000L, 1000L * attempt))
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    /**
     * Jump to the newest update so a restart never trades yesterday's posts.
     * If the bot still has a webhook installed getUpdates fails with 409;
     * in that case we drop the webhook (a bot can use polling or a webhook,
     * never both) and continue.
     */
    private fun prime() {
        val url = baseUrl("getUpdates")
            .addQueryParameter("offset", "-1")
            .addQueryParameter("limit", "1")
            .addQueryParameter("timeout", "0")
            .build()
        val body = get(url.toString(), allowRetry = true) ?: return
        val arr = body.optJSONArray("result") ?: return
        var maxId = offset
        for (i in 0 until arr.length()) {
            maxId = maxOf(maxId, arr.getJSONObject(i).optLong("update_id", 0L))
        }
        offset = maxId + 1
        LogBus.log("تلگرام: آماده‌سازی انجام شد (offset=$offset)")
    }

    private fun baseUrl(method: String): HttpUrl.Builder =
        HttpUrl.Builder()
            .scheme("https")
            .host("api.telegram.org")
            .addPathSegment("bot$token")
            .addPathSegment(method)

    /** One long-poll round. Returns true when Telegram answered normally. */
    private fun pollOnce(): Boolean {
        val url = baseUrl("getUpdates")
            .addQueryParameter("timeout", "30")
            .addQueryParameter("limit", "20")
            .addQueryParameter("offset", offset.toString())
            .addQueryParameter("allowed_updates", "[\"channel_post\"]")
            .build()

        val body = get(url.toString(), allowRetry = true)
        onState(true, "متصل به کانال")
        if (body == null) return true

        val arr = body.optJSONArray("result") ?: return true
        for (i in 0 until arr.length()) {
            val upd = arr.getJSONObject(i)
            val id = upd.optLong("update_id", 0L)
            if (id >= offset) offset = id + 1
            try {
                handleUpdate(upd)
            } catch (e: Exception) {
                LogBus.log("تلگرام: پست نامعتبر (${e.message})")
            }
        }
        return true
    }

    private fun handleUpdate(upd: JSONObject) {
        val post = upd.optJSONObject("channel_post") ?: return
        val msgId = post.optLong("message_id", -1L)
        if (msgId <= 0 || !seenIds.add(msgId)) return
        while (seenIds.size > 800) {
            val first = seenIds.first()
            seenIds.remove(first)
        }
        if (!isOurChannel(post.optJSONObject("chat"))) return

        val when0 = post.optLong("date", 0L)
        if (when0 > 0 && System.currentTimeMillis() / 1000 - when0 > maxAgeSec) {
            LogBus.log("تلگرام: پست قدیمی (${msgId}) نادیده گرفته شد")
            return
        }

        val text = post.optString("text", "")
        if (text.isBlank()) return
        if (!SignalParser.looksTradeable(text)) return

        val sig = SignalParser.fromChannelMessage(text) ?: run {
            LogBus.log("تلگرام: پست ${msgId} قابل تبدیل به سیگنال نبود")
            return
        }
        if (sig.score != null && sig.score < minScore) {
            LogBus.log("تلگرام: ${sig.symbol} نمرهٔ ${sig.score} < حداقل $minScore — رد شد")
            return
        }
        val now = System.currentTimeMillis()
        val last = lastFire[sig.symbol]
        if (last != null && now - last < cooldownSec * 1000) {
            LogBus.log("تلگرام: ${sig.symbol} داخل کول‌داون است — رد شد")
            return
        }
        lastFire[sig.symbol] = now

        LogBus.log("⚡ سیگنال تلگرام: ${sig.bitunixSymbol} ${sig.direction} " +
                "entry=${SignalFormat.fmt(sig.entryPrice)} tp=${SignalFormat.fmt(sig.tpPrice)} " +
                "sl=${SignalFormat.fmt(sig.slPrice)} score=${sig.score}")
        onSignal(sig)
    }

    private fun isOurChannel(chat: JSONObject?): Boolean {
        if (chat == null) return false
        val want = channel.trim()
        val id = chat.optLong("id", 0L).toString()
        val user = chat.optString("username", "")
        return id == want ||
                ("@" + user).equals(want, true) ||
                user.equals(want.removePrefix("@"), true) ||
                want.isEmpty()
    }

    /** GET with one automatic webhook-removal retry on HTTP 409. */
    private fun get(url: String, allowRetry: Boolean): JSONObject? {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { resp ->
            val raw = resp.body?.string() ?: ""
            val json = runCatching { JSONObject(raw) }.getOrNull()
                ?: throw IllegalStateException("HTTP ${resp.code}")
            if (json.optBoolean("ok", false)) return json
            val desc = json.optString("description", "")
            if (resp.code == 409 && allowRetry) {
                LogBus.log("تلگرام: webhook فعال است — حذف آن و ادامه با polling")
                deleteWebhook()
                val again = get(url, allowRetry = false)
                return again
            }
            throw IllegalStateException("${resp.code}: $desc")
        }
    }

    private fun deleteWebhook() {
        runCatching {
            val url = baseUrl("deleteWebhook").build().toString()
            val req = Request.Builder().url(url).get().build()
            client.newCall(req).execute().use { it.body?.string() }
        }
    }
}
