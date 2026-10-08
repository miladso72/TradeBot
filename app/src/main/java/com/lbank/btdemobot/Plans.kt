package com.lbank.btdemobot

/**
 * Built-in action plans. Plain Kotlin (no Android classes) so the JVM unit
 * tests can verify that every placeholder in the plan is resolvable.
 */
object Plans {

    /**
     * Full "open a $10 isolated 10x LIMIT long with TP/SL" flow.
     *
     * Selector texts are the Bitunix app's English labels. After ONE ui-dump
     * (button "ذخیره‌ی ساختار صفحه") you will see the real text/desc/id values
     * and can correct any step that reports NO. Steps with `"optional": true`
     * never abort the plan.
     */
    val DEFAULT_PLAN: String = """
    {
      "name": "bitunix-limit-10x-isolated",
      "steps": [
        {"action":"launch","delay":3000},
        {"action":"wait","by":"text_contains","value":"Futures","timeout":10000,"delay":300,"optional":true},
        {"action":"click","by":"text_contains","value":"Futures","delay":1200,"optional":true},

        {"action":"click","by":"desc_contains","value":"Search","delay":1000,"optional":true},
        {"action":"clearText","by":"class_contains","value":"EditText","timeout":5000,"delay":200},
        {"action":"setText","by":"class_contains","value":"EditText","text":"{BITUNIX_SYMBOL}","delay":1200},
        {"action":"click","by":"text_contains","value":"{BITUNIX_SYMBOL}","delay":2000},

        {"action":"click","by":"text_contains","value":"{LEVERAGE}x","delay":900,"optional":true},
        {"action":"click","by":"desc_contains","value":"Isolated","delay":600,"optional":true},
        {"action":"click","by":"text","value":"Isolated","delay":600,"optional":true},
        {"action":"click","by":"class_contains","value":"EditText","timeout":3000,"delay":300,"optional":true},
        {"action":"clearText","by":"class_contains","value":"EditText","timeout":3000,"delay":200,"optional":true},
        {"action":"setText","by":"class_contains","value":"EditText","text":"{LEVERAGE}","timeout":3000,"delay":400,"optional":true},
        {"action":"click","by":"text","value":"{CONFIRM_LABEL}","delay":900,"optional":true},
        {"action":"back","delay":600,"optional":true},

        {"action":"click","by":"text","value":"{ORDER_TYPE_LABEL}","delay":900},
        {"action":"clearText","by":"text_contains","value":"Price","timeout":5000,"delay":200,"optional":true},
        {"action":"setText","by":"text_contains","value":"Price","text":"{ENTRY_PRICE}","timeout":6000,"delay":600},

        {"action":"clearText","by":"text_contains","value":"Amount","timeout":5000,"delay":200,"optional":true},
        {"action":"setText","by":"text_contains","value":"Amount","text":"{QTY}","timeout":6000,"delay":600},

        {"action":"click","by":"desc_contains","value":"TP/SL","delay":900,"optional":true},
        {"action":"click","by":"text_contains","value":"Take Profit","delay":600,"optional":true},
        {"action":"setText","by":"text_contains","value":"Take Profit","text":"{TP_PRICE}","timeout":4000,"delay":400,"optional":true},
        {"action":"setText","by":"text_contains","value":"Stop Loss","text":"{SL_PRICE}","timeout":4000,"delay":400,"optional":true},
        {"action":"click","by":"text","value":"{CONFIRM_LABEL}","delay":900,"optional":true},

        {"action":"click","by":"text","value":"{SIDE_LABEL}","timeout":6000,"delay":1000},
        {"action":"click","by":"text_contains","value":"{CONFIRM_LABEL}","timeout":8000,"delay":1500,"optional":true}
      ]
    }
    """.trimIndent()
}
