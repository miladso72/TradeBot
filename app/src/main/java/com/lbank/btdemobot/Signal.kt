package com.lbank.btdemobot

import org.json.JSONObject

/**
 * A trading signal, however it arrived.
 *
 *  symbol        : LBank symbol, e.g. "SOL_USDT"
 *  bitunixSymbol : Bitunix symbol, e.g. "SOLUSDT"
 *  entryPrice    : entry price from the signal (absolute)
 *  tpPrice       : take-profit price, absolute (from the signal)
 *  slPrice       : stop-loss / invalidation price, absolute
 *
 * tp/sl are the numbers the LBank bot already publishes ("حد خروج تقریبی" /
 * "حد ابطال تقریبی") plus the machine-readable #SIG line, so no extra API is
 * needed on the phone.
 */
data class Signal(
    val id: String,
    val symbol: String,
    val bitunixSymbol: String,
    val direction: String,
    val entryPrice: Double? = null,
    val tpPrice: Double? = null,
    val slPrice: Double? = null,
    val tpPct: Double? = null,
    val slPct: Double? = null,
    val score: Int? = null,
    /** "tg" (Telegram channel) or "ws" (PC WebSocket bridge) */
    val source: String = "ws",
    val firstSeen: Double = 0.0,
    val receivedAt: Long = System.currentTimeMillis()
) {
    /** everything the action plan needs to place a LIMIT order */
    fun isTradeable(): Boolean = entryPrice != null && entryPrice > 0.0

    companion object {

        /** LBank "SOL_USDT" -> Bitunix "SOLUSDT" (Bitunix futures naming) */
        fun toBitunixSymbol(symbol: String): String =
            symbol.uppercase()
                .replace("_USDT", "USDT")
                .replace("_", "")
                .trim()

        private fun JSONObject.optDoubleOrNull(key: String): Double? =
            if (isNull(key)) null else optDouble(key).takeIf { !it.isNaN() }

        /** Signal as pushed by the PC bridge (`type: "signal"`). */
        fun fromJson(o: JSONObject): Signal? {
            val symbol = o.optString("symbol", "")
            if (symbol.isEmpty()) return null
            return Signal(
                id = o.optString("id", symbol),
                symbol = symbol,
                bitunixSymbol = o.optString(
                    "bitunix_symbol", toBitunixSymbol(symbol)
                ),
                direction = o.optString("direction", "LONG"),
                entryPrice = o.optDoubleOrNull("entry_price"),
                tpPrice = o.optDoubleOrNull("tp_price"),
                slPrice = o.optDoubleOrNull("sl_price"),
                tpPct = o.optDoubleOrNull("tp_pct"),
                slPct = o.optDoubleOrNull("sl_pct"),
                score = if (o.has("score")) o.optInt("score") else null,
                source = "ws",
                firstSeen = o.optDouble("first_seen", 0.0),
                receivedAt = System.currentTimeMillis()
            )
        }
    }
}
