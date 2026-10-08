package com.lbank.btdemobot

import org.json.JSONArray
import org.json.JSONObject

/**
 * One UI-automation step. `action` is one of:
 *   launch    – open the target app (uses the configured package)
 *   click     – find a node by selector and tap it
 *   setText   – find a node by selector and set its text (text = value)
 *   clearText – empty an already-filled field (use before setText)
 *   tapPct    – tap a spot on the screen, value = "x,y" as 0..1 fractions
 *   swipe     – value = "x1,y1,x2,y2" as 0..1 fractions (scroll)
 *   wait      – wait until a node matching the selector exists (no tap)
 *   sleep     – just wait delayMs
 *   back      – press Back
 *   home      – press Home
 *
 * `by` (selector): text | text_contains | id | id_contains |
 *                  desc | desc_contains | class_contains
 *
 * `optional: true` => if the step fails the plan keeps going (needed for
 * toggles that may already be in the wanted state, e.g. "Isolated").
 *
 * Placeholders allowed inside value/text are listed in [SignalFormat].
 */
data class Step(
    val action: String,
    val by: String = "text",
    val value: String = "",
    val text: String = "",
    val pkg: String = "",
    val delayMs: Long = 500,
    val timeoutMs: Long = 8000,
    val optional: Boolean = false,
    val durationMs: Long = 300
)

class ActionPlan(val name: String, val steps: List<Step>) {
    companion object {
        fun fromJson(json: String): ActionPlan {
            val o = JSONObject(json)
            val arr: JSONArray = o.optJSONArray("steps") ?: JSONArray()
            val list = ArrayList<Step>(arr.length())
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                list.add(
                    Step(
                        action = s.optString("action", ""),
                        by = s.optString("by", "text"),
                        value = s.optString("value", ""),
                        text = s.optString("text", ""),
                        pkg = s.optString("package", ""),
                        delayMs = s.optLong("delay", 500),
                        timeoutMs = s.optLong("timeout", 8000),
                        optional = s.optBoolean("optional", false),
                        durationMs = s.optLong("duration", 300)
                    )
                )
            }
            return ActionPlan(o.optString("name", "plan"), list)
        }
    }
}
