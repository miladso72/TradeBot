package com.lbank.btdemobot

import org.json.JSONObject

/**
 * Turns one Telegram channel message from the LBank bot into a [Signal].
 *
 * Pure functions on purpose: no Android classes, so it can be unit-tested on
 * the JVM against a real message produced by lbank_spot_signals.py.
 *
 * Two parsing paths:
 *   1. the machine-readable line the bot appends:  #SIG {"t":"SIG","s":...}
 *   2. a tolerant fallback that reads the human-readable HTML fields
 *      ("مقدار ورود", "حد خروج تقریبی", "حد ابطال تقریبی")
 *
 * Messages marked unsafe ("ناامن") never produce a signal — those are the
 * "get out" notices, not entries.
 */
object SignalParser {

    const val SIG_PREFIX = "#SIG"
    const val UNSAFE_MARKER = "ناامن"

    private val rxSig = Regex("""#SIG\s*(\{.*?\})""", RegexOption.DOT_MATCHES_ALL)

    // 🟢 <b>لانگ اسپات</b> — <b>SOL/USDT</b>
    private val rxHeader = Regex("""<b>\s*([A-Z0-9]{2,20})\s*/\s*([A-Z0-9]{2,10})\s*</b>""")

    // 💰 مقدار ورود: <b>12.0574</b>
    private val rxEntry = Regex("""مقدار ورود[^<]*<b>\s*([0-9][0-9.,]*)\s*</b>""")

    // 🎯 حد خروج تقریبی: <b>12.2383</b> (≈ 1.5%)
    private val rxTp = Regex("""حد خروج تقریبی[^<]*<b>\s*([0-9][0-9.,]*)\s*</b>""" +
            """(?:\s*\(≈\s*(-?[0-9.]+)\s*%\))?""")

    // 🛑 حد ابطال تقریبی: <b>10.9851</b> (≈ 6.0%)
    private val rxSl = Regex("""حد ابطال تقریبی[^<]*<b>\s*([0-9][0-9.,]*)\s*</b>""" +
            """(?:\s*\(≈\s*(-?[0-9.]+)\s*%\))?""")

    private val rxScore = Regex("""نمره[^<]*<b>\s*([0-9]{1,2})\s*/\s*([0-9]{1,2})\s*</b>""")

    /** A price/ratio as printed by the bot ("12.0574", "1,234.5"). */
    private fun num(s: String?): Double? =
        s?.replace(",", "")?.trim()?.toDoubleOrNull()

    /** Is this message the bot's "unsafe, exit now" notice? */
    fun isUnsafe(text: String): Boolean = text.contains(UNSAFE_MARKER)

    /** Does this message advertise a tradeable entry? */
    fun looksTradeable(text: String): Boolean =
        text.contains(SIG_PREFIX) || (text.contains("لانگ اسپات") && rxEntry.containsMatchIn(text))

    /** The raw #SIG JSON payload, if the bot included one. */
    fun sigPayload(text: String): JSONObject? {
        val m = rxSig.find(text) ?: return null
        return runCatching { JSONObject(m.groupValues[1]) }.getOrNull()
    }

    fun fromChannelMessage(text: String, source: String = "tg"): Signal? {
        if (text.isBlank() || isUnsafe(text)) return null

        sigPayload(text)?.let { p ->
            val sym = p.optString("s", "")
            if (sym.isNotEmpty()) {
                return Signal(
                    id = sym,
                    symbol = sym,
                    bitunixSymbol = Signal.toBitunixSymbol(sym),
                    direction = p.optString("d", "LONG").uppercase(),
                    entryPrice = p.optDouble("e").takeIf { it > 0 },
                    tpPrice = p.optDouble("tp").takeIf { it > 0 },
                    slPrice = p.optDouble("sl").takeIf { it > 0 },
                    score = if (p.has("q")) p.optInt("q") else null,
                    source = source
                )
            }
        }

        // ---- fallback: human-readable fields ----
        val hdr = rxHeader.find(text) ?: return null
        val symbol = hdr.groupValues[1].uppercase() + "_" + hdr.groupValues[2].uppercase()
        val entry = num(rxEntry.find(text)?.groupValues?.get(1)) ?: return null
        val tpM = rxTp.find(text)
        val slM = rxSl.find(text)
        return Signal(
            id = symbol,
            symbol = symbol,
            bitunixSymbol = Signal.toBitunixSymbol(symbol),
            direction = "LONG",
            entryPrice = entry,
            tpPrice = num(tpM?.groupValues?.get(1)),
            slPrice = num(slM?.groupValues?.get(1)),
            tpPct = num(tpM?.groupValues?.getOrNull(2)),
            slPct = num(slM?.groupValues?.getOrNull(2)),
            score = rxScore.find(text)?.groupValues?.get(1)?.toIntOrNull(),
            source = source
        )
    }
}
