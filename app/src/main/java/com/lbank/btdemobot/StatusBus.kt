package com.lbank.btdemobot

/** Shared connection/telemetry state that the UI polls. */
object StatusBus {
    @Volatile var connected: Boolean = false        // PC bridge WebSocket
    @Volatile var text: String = "قطع"

    @Volatile var tgConnected: Boolean = false      // Telegram channel poll
    @Volatile var tgText: String = "قطع"

    @Volatile var signals: Int = 0
    @Volatile var executed: Int = 0
    @Volatile var lastSignal: String = ""
}
