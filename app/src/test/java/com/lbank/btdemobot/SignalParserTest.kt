package com.lbank.btdemobot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * JVM-only tests (run with `./gradlew test`) on the sample message the real
 * Python bot renders, so the exact numbers typed into the Bitunix app are
 * pinned before anything is installed on a phone.
 */
class SignalParserTest {

    private val sample: String by lazy {
        javaClass.getResourceAsStream("/sample_signal_msg.txt")
            ?.bufferedReader()?.readText()
            ?: error("app/src/test/resources/sample_signal_msg.txt is missing")
    }

    private val cfg = TradeCfg(marginUsdt = 10.0, leverage = 10, orderType = "limit", isolated = true)

    // ---------------------------------------------------------------- parsing

    @Test
    fun `reads the machine readable SIG line`() {
        val sig = SignalParser.fromChannelMessage(sample)
        assertNotNull("SIG line should parse", sig)
        sig!!
        assertEquals("SAMPLE_USDT", sig.symbol)
        assertEquals("SAMPLEUSDT", sig.bitunixSymbol)
        assertEquals("LONG", sig.direction)
        assertEquals(12.0574242964, sig.entryPrice!!, 1e-9)
        assertEquals(12.2382856609, sig.tpPrice!!, 1e-9)
        assertEquals(10.9851483193, sig.slPrice!!, 1e-9)
        assertEquals(10, sig.score)
        assertEquals("tg", sig.source)
        assertTrue(sig.isTradeable())
    }

    @Test
    fun `fallback parses the human readable html fields`() {
        val noSig = sample.lines().filterNot { it.contains("#SIG") }.joinToString("\n")
        val sig = SignalParser.fromChannelMessage(noSig)
        assertNotNull("HTML fallback should still find an entry", sig)
        sig!!
        assertEquals("SAMPLE_USDT", sig.symbol)
        assertEquals(12.0574, sig.entryPrice!!, 1e-6)
        assertEquals(12.2383, sig.tpPrice!!, 1e-6)
        assertEquals(10.9851, sig.slPrice!!, 1e-6)
        assertEquals(1.5, sig.tpPct!!, 1e-6)
        assertEquals(6.0, sig.slPct!!, 1e-6)
        assertEquals(10, sig.score)
    }

    @Test
    fun `unsafe notice never becomes a signal`() {
        assertTrue(SignalParser.isUnsafe("⚠️ ناامن — این ارز ناامن شد، سریعاً خارج شوید"))
        assertNull(
            SignalParser.fromChannelMessage(
                "⚠️ <b>ناامن</b> — SAMPLE/USDT ناامن شد\n<code>#SIG {\"s\":\"SAMPLE_USDT\",\"e\":12.0}</code>"
            )
        )
    }

    @Test
    fun `garbage does not throw and yields nothing`() {
        assertNull(SignalParser.fromChannelMessage(""))
        assertNull(SignalParser.fromChannelMessage("hello world"))
        assertNull(SignalParser.fromChannelMessage("#SIG {not json}"))
    }

    // -------------------------------------------------------------- formatting

    @Test
    fun `quantity is notional over entry price`() {
        val sig = SignalParser.fromChannelMessage(sample)!!
        val expected = cfg.notional / sig.entryPrice!!
        val got = SignalFormat.qty(sig, cfg).toDouble()
        assertEquals(100.0, cfg.notional, 1e-9)
        assertEquals(expected, got, 1e-4)

        val q = SignalFormat.qty(sig, cfg)
        assertFalse("no scientific notation: $q", q.contains("E") || q.contains("e"))
        assertTrue("must be a positive number: $q", q.toDouble() > 0)
    }

    @Test
    fun `prices are printed as plain decimals`() {
        val sig = SignalParser.fromChannelMessage(sample)!!
        assertEquals("12.0574243", SignalFormat.fmt(sig.entryPrice))
        assertEquals("12.23828566", SignalFormat.fmt(sig.tpPrice))
        assertEquals("10.98514832", SignalFormat.fmt(sig.slPrice))
        assertEquals("0.000012", SignalFormat.fmt(0.000012))
        assertEquals("1234.5", SignalFormat.fmt(1234.50))
    }

    @Test
    fun `placeholders resolve for a limit isolated 10x long`() {
        val sig = SignalParser.fromChannelMessage(sample)!!
        val v = SignalFormat.values(sig, cfg)
        assertEquals("SAMPLE_USDT", v["{SYMBOL}"])
        assertEquals("SAMPLEUSDT", v["{BITUNIX_SYMBOL}"])
        assertEquals("SAMPLE", v["{BASE}"])
        assertEquals("Buy", v["{SIDE_LABEL}"])
        assertEquals("Limit", v["{ORDER_TYPE_LABEL}"])
        assertEquals("Isolated", v["{ISOLATED_LABEL}"])
        assertEquals("10", v["{LEVERAGE}"])
        assertEquals("100", v["{NOTIONAL}"])
        assertEquals("Confirm", v["{CONFIRM_LABEL}"])
        assertEquals("12.0574243", v["{ENTRY_PRICE}"])
        assertEquals("12.23828566", v["{TP_PRICE}"])
        assertEquals("10.98514832", v["{SL_PRICE}"])
        assertEquals("10", v["{SCORE}"])
    }

    @Test
    fun `subst replaces known and leaves unknown placeholders untouched`() {
        val sig = SignalParser.fromChannelMessage(sample)!!
        assertEquals("SAMPLEUSDT", SignalFormat.subst("{BITUNIX_SYMBOL}", sig, cfg))
        assertEquals("Buy 8.293645 SAMPLEUSDT",
            SignalFormat.subst("{SIDE_LABEL} {QTY} {BITUNIX_SYMBOL}", sig, cfg))
        assertEquals("{NOPE}", SignalFormat.subst("{NOPE}", sig, cfg))
        assertEquals("", SignalFormat.subst("", sig, cfg))
    }

    @Test
    fun `bitunix symbol conversion`() {
        assertEquals("SOLUSDT", Signal.toBitunixSymbol("sol_usdt"))
        assertEquals("BTCUSDT", Signal.toBitunixSymbol("BTC_USDT"))
        assertEquals("1000PEPEUSDT", Signal.toBitunixSymbol("1000PEPE_USDT"))
    }

    // ------------------------------------------------------------- action plan

    @Test
    fun `built-in plan parses and every placeholder is resolvable`() {
        val sig = SignalParser.fromChannelMessage(sample)!!
        val plan = ActionPlan.fromJson(Plans.DEFAULT_PLAN)

        assertEquals("bitunix-limit-10x-isolated", plan.name)
        assertTrue("plan should have steps", plan.steps.size >= 15)

        val rxLeftover = Regex("""\{[A-Z_]+\}""")
        for ((i, s) in plan.steps.withIndex()) {
            for (field in listOf(SignalFormat.subst(s.value, sig, cfg), SignalFormat.subst(s.text, sig, cfg))) {
                assertFalse(
                    "step #$i (${s.action}) has an unknown placeholder: $field",
                    rxLeftover.containsMatchIn(field)
                )
            }
            assertTrue("step #$i has an action", s.action.isNotBlank())
        }

        // the two steps that actually move money must NOT be optional
        val critical = plan.steps.filter { it.action == "setText" && it.text.isNotEmpty() }
        assertTrue(
            "entry price, quantity and the order click must be mandatory",
            critical.count { !it.optional } >= 2
        )
    }

    @Test
    fun `qa quantity matches expected value written into the app`() {
        val sig = SignalParser.fromChannelMessage(sample)!!
        val q = SignalFormat.qty(sig, cfg)
        // 10 USDT margin * 10x = 100 USDT notional on a $12.0574 asset
        assertEquals("8.293645", q)
        val rounded = BigDecimal(q).setScale(4, RoundingMode.HALF_UP)
        assertEquals(BigDecimal("8.2936"), rounded)
        assertTrue(BigDecimal(q).multiply(BigDecimal("12.0574242964")).toDouble() in 99.9..100.1)
    }
}
