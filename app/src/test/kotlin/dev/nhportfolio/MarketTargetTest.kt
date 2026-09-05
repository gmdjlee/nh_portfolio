package dev.nhportfolio

import dev.nhportfolio.market.Action
import dev.nhportfolio.market.Breadth
import dev.nhportfolio.market.Signal
import dev.nhportfolio.market.Verdict
import dev.nhportfolio.market.verdictText
import dev.nhportfolio.portfolio.Rebalance
import dev.nhportfolio.portfolio.heldBp
import dev.nhportfolio.portfolio.withMarketTarget
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun signal(
    targetBp: Int,
    window: Int = Breadth.PCT_WIN,
    asOf: String = "20260101",
) = Signal(targetBp = targetBp, band = Breadth.bandOf(targetBp), breadth = 0.0, pctile = 0.0, window = window, asOf = asOf)

private fun line(
    key: String,
    weightBp: Int,
) = Rebalance.Line(key = key, currentAmt = 0, weightBp = weightBp, targetBp = null, deltaShares = null)

/** 종목 두 개(합 7_500bp) + 현금 행. heldBp 가 실제 비중을 쓸 때와 예수금 목표를 쓸 때 값이
 *  갈리도록 일부러 서로 다른 숫자로 잡는다 — 같았다면 회귀를 잡지 못한다. */
private val PLAN =
    Rebalance.Plan(
        lines = listOf(line("A", 6_000), line("B", 1_500), line(Rebalance.CASH, 2_500)),
        total = 0,
        cashAfter = 0,
        targetSumBp = 0,
    )

class MarketTargetTest {
    // ---- withMarketTarget ----

    @Test
    fun `노출 목표는 예수금 목표로 뒤집혀 전달된다`() {
        // FULL_BP - exposureBp 가 아니라 exposureBp 를 그대로 넘기는 실수를 잡는 테스트다.
        val out = withMarketTarget(emptyMap(), exposureBp = 1_250, weightsBp = mapOf("A" to 5_000, "B" to 5_000))

        assertEquals(1_250, out.filterKeys { it != Rebalance.CASH }.values.sum())
        assertEquals(8_750, out.getValue(Rebalance.CASH))
    }

    // ---- heldBp ----

    @Test
    fun `예수금 목표가 있으면 유지 비중은 100 에서 그 값을 뺀 것이다`() {
        val (bp, fromCashTarget) = heldBp(mapOf(Rebalance.CASH to 7_500), PLAN)

        assertEquals(2_500, bp)
        assertTrue(fromCashTarget)
    }

    @Test
    fun `예수금 목표가 없으면 유지 비중은 실제 주식 비중이다`() {
        // 실제 비중으로 바꾸는 회귀를 잡는다 — 위 테스트는 같은 PLAN 에서 2_500 을 요구하므로
        // "항상 실제 비중을 쓴다"로 되돌리면 두 테스트 중 하나가 반드시 깨진다.
        val (bp, fromCashTarget) = heldBp(emptyMap(), PLAN)

        assertEquals(7_500, bp)
        assertFalse(fromCashTarget)
    }

    // ---- verdictText: 기본 문구 ----

    @Test
    fun `HOLD 는 실행하지 않는다고 알린다`() {
        val (title, detail) =
            verdictText(signal(5_000), Verdict(Action.HOLD, gapBp = 500), fromCashTarget = true, today = LocalDate.of(2026, 1, 1))

        assertEquals("실행하지 않습니다", title)
        assertEquals(
            "목표 50.00% · 유지 45.00% · 차이 5.00%p. 15%p 기준에 미달하므로 기록만 남깁니다. 기준 2026-01-01.",
            detail,
        )
    }

    @Test
    fun `CUT 은 목표 비중으로 줄이고 하루 안에 실행한다고 알린다`() {
        val (title, detail) =
            verdictText(signal(3_000), Verdict(Action.CUT, gapBp = -2_000), fromCashTarget = true, today = LocalDate.of(2026, 1, 1))

        assertEquals("비중을 30.00%로 줄입니다", title)
        assertEquals(
            "목표 30.00% · 유지 50.00% · 차이 20.00%p. 당일 또는 익일에 한 번에 실행합니다. 기준 2026-01-01.",
            detail,
        )
    }

    @Test
    fun `ADD 는 목표 비중으로 늘리고 분할 매수한다고 알린다`() {
        val (title, detail) =
            verdictText(signal(8_000), Verdict(Action.ADD, gapBp = 2_000), fromCashTarget = true, today = LocalDate.of(2026, 1, 1))

        assertEquals("비중을 80.00%로 늘립니다", title)
        assertEquals(
            "목표 80.00% · 유지 60.00% · 차이 20.00%p. 3영업일 간격으로 3회에 나누어 채웁니다. 기준 2026-01-01.",
            detail,
        )
    }

    // ---- verdictText: 덧붙는 문구 ----

    @Test
    fun `적용한 목표가 없으면 실제 비중과 비교한다는 문구가 붙는다`() {
        val (_, detail) =
            verdictText(signal(5_000), Verdict(Action.HOLD, gapBp = 500), fromCashTarget = false, today = LocalDate.of(2026, 1, 1))

        assertTrue(detail.contains("(적용한 목표가 없어 실제 비중과 비교합니다)"), detail)
    }

    @Test
    fun `백분위 창이 다 안 찼으면 창 크기를 붙인다`() {
        val (_, detail) =
            verdictText(
                signal(5_000, window = 500),
                Verdict(Action.HOLD, gapBp = 500),
                fromCashTarget = true,
                today = LocalDate.of(2026, 1, 1),
            )

        assertTrue(detail.contains("백분위 창 500/756일."), detail)
    }

    @Test
    fun `기준일로부터 7일이 지나면 갱신을 권한다`() {
        val (_, detail) =
            verdictText(
                signal(5_000, asOf = "20260101"),
                Verdict(Action.HOLD, gapBp = 500),
                fromCashTarget = true,
                today = LocalDate.of(2026, 1, 8),
            )

        assertTrue(detail.endsWith("기준 2026-01-01, 7일 경과 — 갱신을 권합니다."), detail)
    }

    @Test
    fun `6일까지는 갱신 권유 없이 기준일만 보여준다`() {
        val (_, detail) =
            verdictText(
                signal(5_000, asOf = "20260101"),
                Verdict(Action.HOLD, gapBp = 500),
                fromCashTarget = true,
                today = LocalDate.of(2026, 1, 7),
            )

        assertTrue(detail.endsWith("기준 2026-01-01."), detail)
        assertFalse(detail.contains("경과"), detail)
    }
}
