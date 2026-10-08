package com.lbank.btdemobot

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Tiny in-memory + file log bus so both the UI and the accessibility
 *  service can write to one place. */
object LogBus {
    private const val MAX = 500
    private val lines = ArrayDeque<String>()
    private val listeners = LinkedHashSet<(String) -> Unit>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    fun log(msg: String) {
        val line = "${fmt.format(Date())}  $msg"
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
        for (l in listeners.toList()) {
            runCatching { l(line) }
        }
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()

    @Synchronized
    fun clear() {
        lines.clear()
        for (l in listeners.toList()) {
            runCatching { l("") }
        }
    }

    @Synchronized
    fun addListener(l: (String) -> Unit) { listeners.add(l) }

    @Synchronized
    fun removeListener(l: (String) -> Unit) { listeners.remove(l) }

    /** append a line to <external>/btdemobot.log (best effort) */
    fun fileLine(ctx: Context, msg: String) {
        runCatching {
            val f = File(ctx.getExternalFilesDir(null), "btdemobot.log")
            f.appendText(msg + "\n")
        }
    }
}
