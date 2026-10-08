package com.lbank.btdemobot

import android.content.Context
import android.content.SharedPreferences

/**
 * All app settings. Defaults already match what was asked for:
 *   $10 margin, isolated 10x, LIMIT order, TP/SL taken from the signal,
 *   signals read directly from the Telegram channel.
 */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.getSharedPreferences("btdemobot", Context.MODE_PRIVATE)

    // ------------------------------------------------------------ signal source
    /** telegram | ws | both */
    var sourceMode: String
        get() = sp.getString("sourceMode", "telegram") ?: "telegram"
        set(v) = sp.edit().putString("sourceMode", v).apply()

    /** PC bridge WebSocket (only used in ws/both mode) */
    var serverUrl: String
        get() = sp.getString("serverUrl", "ws://192.168.1.10:8770/ws") ?: ""
        set(v) = sp.edit().putString("serverUrl", v).apply()

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v).apply()

    /** Telegram bot token that is an admin of the signal channel */
    var tgToken: String
        get() = sp.getString("tgToken", DEFAULT_TG_TOKEN) ?: ""
        set(v) = sp.edit().putString("tgToken", v).apply()

    /** @channel or numeric chat id */
    var tgChannel: String
        get() = sp.getString("tgChannel", "@TraderProMeBo") ?: ""
        set(v) = sp.edit().putString("tgChannel", v).apply()

    // ------------------------------------------------------------ order settings
    var targetPackage: String
        get() = sp.getString("targetPackage", "io.bitunix.android") ?: ""
        set(v) = sp.edit().putString("targetPackage", v).apply()

    /** margin per trade in USDT (the user asked for $10) */
    var marginUsdt: Double
        get() = sp.getString("marginUsdt", "10")?.toDoubleOrNull() ?: 10.0
        set(v) = sp.edit().putString("marginUsdt", v.toString()).apply()

    /** isolated leverage (the user asked for 10x) */
    var leverage: Int
        get() = sp.getInt("leverage", 10)
        set(v) = sp.edit().putInt("leverage", v).apply()

    /** limit | market */
    var orderType: String
        get() = sp.getString("orderType", "limit") ?: "limit"
        set(v) = sp.edit().putString("orderType", v).apply()

    /** isolated margin mode on/off */
    var isolated: Boolean
        get() = sp.getBoolean("isolated", true)
        set(v) = sp.edit().putBoolean("isolated", v).apply()

    /** also push the TP/SL prices into the order form */
    var setTpSl: Boolean
        get() = sp.getBoolean("setTpSl", true)
        set(v) = sp.edit().putBoolean("setTpSl", v).apply()

    // ------------------------------------------------------------ filters
    /** minimum indicator score to act on (9 => only 9/10 and 10/10) */
    var minScore: Int
        get() = sp.getInt("minScore", 9)
        set(v) = sp.edit().putInt("minScore", v).apply()

    /** ignore a repeat of the same symbol for N seconds */
    var cooldownSec: Long
        get() = sp.getLong("cooldownSec", 300L)
        set(v) = sp.edit().putLong("cooldownSec", v).apply()

    /** max age of a channel post that may still be traded, seconds */
    var maxAgeSec: Long
        get() = sp.getLong("maxAgeSec", 600L)
        set(v) = sp.edit().putLong("maxAgeSec", v).apply()

    // ------------------------------------------------------------ switches
    /** master switch: actually run the action plan when a signal arrives */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()

    /** dry run: log the plan but do not tap anything */
    var dryRun: Boolean
        get() = sp.getBoolean("dryRun", true)
        set(v) = sp.edit().putBoolean("dryRun", v).apply()

    var planJson: String
        get() = sp.getString("planJson", "") ?: ""
        set(v) = sp.edit().putString("planJson", v).apply()

    // ------------------------------------------------------------ derived
    fun tradeCfg(): TradeCfg = TradeCfg(
        marginUsdt = marginUsdt,
        leverage = leverage,
        orderType = orderType,
        isolated = isolated,
        confirmLabel = "Confirm"
    )

    companion object {
        /** token of the user's own signal bot (TraderProMeBot) */
        const val DEFAULT_TG_TOKEN =
            "8874546008:AAHu-42zsvjJ4QDLmd8HVOg0ZPwMg3rtrrs"
    }
}
