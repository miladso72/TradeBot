package com.lbank.btdemobot

import java.math.BigDecimal
import java.math.RoundingMode

/** User's order configuration (came from the settings screen / Prefs). */
data class TradeCfg(
    val marginUsdt: Double = 10.0,
    val leverage: Int = 10,
    val orderType: String = "limit",   // limit | market
    val isolated: Boolean = true,
    val confirmLabel: String = "Confirm"
) {
    val notional: Double get() = marginUsdt * leverage
}

/**
 * Placeholder engine for the action plan.
 *
 * Every `{NAME}` inside a step's `value`/`text` is replaced from the signal
 * plus the user config. Pure Kotlin (no Android), so the unit tests can pin
 * the exact numbers that get typed into the Bitunix app.
 *
 *  {SYMBOL}          LBank symbol            SOL_USDT
 *  {BITUNIX_SYMBOL}  Bitunix symbol          SOLUSDT
 *  {BASE}            base coin               SOL
 *  {DIRECTION}       LONG / SHORT
 *  {SIDE_LABEL}      Buy / Sell
 *  {SIDE_LABEL_FA}   خرید / فروش
 *  {MARGIN}          margin in USDT          10
 *  {LEVERAGE}        leverage                10
 *  {NOTIONAL}        margin * leverage       100
 *  {QTY}             real quantity           notional / entry
 *  {ENTRY_PRICE}     entry price             from the signal
 *  {TP_PRICE}        take-profit price       from the signal
 *  {SL_PRICE}        stop-loss price         from the signal
 *  {TP_PCT} {SL_PCT} percentage variants
 *  {ORDER_TYPE}      limit / market
 *  {ORDER_TYPE_LABEL} Limit / Market
 *  {ISOLATED_LABEL}  Isolated / Cross
 *  {CONFIRM_LABEL}   confirm button text
 *  {SCORE}           indicator score x/10
 */
object SignalFormat {

    private val rxPlaceholder = Regex("""\{[A-Z_]+\}""")

    /** decimal string, never scientific notation, trailing zeros removed */
    fun fmt(d: Double, maxDp: Int = 8): String {
        if (d.isNaN() || d.isInfinite()) return "0"
        return BigDecimal(d)
            .setScale(maxDp, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString()
    }

    fun fmt(d: Double?): String = if (d == null) "" else fmt(d)

    /**
     * Quantity to send. For a futures/limit order the size is
     *   notional / entryPrice
     * With $10 margin at 10x that is $100 of exposure.
     * If the signal has no entry price we fall back to the margin amount so
     * the user still sees a number instead of an empty field.
     */
    fun qty(signal: Signal, cfg: TradeCfg, maxDp: Int = 6): String {
        val entry = signal.entryPrice
        if (entry == null || entry <= 0.0) return fmt(cfg.marginUsdt)
        return fmt(cfg.notional / entry, maxDp)
    }

    fun subst(pattern: String, signal: Signal, cfg: TradeCfg): String {
        if (pattern.isEmpty()) return pattern
        val map = values(signal, cfg)
        return rxPlaceholder.replace(pattern) { m -> map[m.value] ?: m.value }
    }

    fun values(signal: Signal, cfg: TradeCfg): Map<String, String> {
        val dir = signal.direction.uppercase()
        val isLong = !dir.contains("SHORT")
        val base = signal.bitunixSymbol.removeSuffix("USDT")
        return mapOf(
            "{SYMBOL}" to signal.symbol,
            "{BITUNIX_SYMBOL}" to signal.bitunixSymbol,
            "{BASE}" to base,
            "{DIRECTION}" to dir,
            "{SIDE_LABEL}" to (if (isLong) "Buy" else "Sell"),
            "{SIDE_LABEL_FA}" to (if (isLong) "خرید" else "فروش"),
            "{MARGIN}" to fmt(cfg.marginUsdt),
            "{LEVERAGE}" to cfg.leverage.toString(),
            "{NOTIONAL}" to fmt(cfg.notional),
            "{QTY}" to qty(signal, cfg),
            "{QTY_RAW}" to qty(signal, cfg, 8),
            "{ENTRY_PRICE}" to fmt(signal.entryPrice),
            "{TP_PRICE}" to fmt(signal.tpPrice),
            "{SL_PRICE}" to fmt(signal.slPrice),
            "{TP_PCT}" to fmt(signal.tpPct),
            "{SL_PCT}" to fmt(signal.slPct),
            "{ORDER_TYPE}" to cfg.orderType.lowercase(),
            "{ORDER_TYPE_LABEL}" to if (cfg.orderType.lowercase() == "market") "Market" else "Limit",
            "{ISOLATED_LABEL}" to if (cfg.isolated) "Isolated" else "Cross",
            "{CONFIRM_LABEL}" to cfg.confirmLabel,
            "{SCORE}" to (signal.score?.toString() ?: "")
        )
    }
}
