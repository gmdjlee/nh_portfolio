package dev.nhportfolio

import dev.nhportfolio.model.Balance
import dev.nhportfolio.model.Holding
import dev.nhportfolio.portfolio.Rebalance
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private fun holding(
    code: String,
    qty: Long,
    price: Long,
    evalAmt: Long = qty * price,
) = Holding(code = code, name = code, qty = qty, remainQty = qty, avgPrice = price, price = price, evalAmt = evalAmt, pnlRate = 0.0)

class RebalanceTest {
    @Test
    fun `분모는 보유 평가금액 합계와 예수금이다`() {
        val b = Balance(cash = 100_000, holdings = listOf(holding("005930", 10, 70_000), holding("000660", 5, 20_000)))
        val plan = Rebalance.plan(b, emptyMap())
        assertEquals(100_000 + 700_000 + 100_000, plan.total)
    }

    @Test
    fun `자산 비중은 basis point 로 계산된다`() {
        val b = Balance(cash = 250_000, holdings = listOf(holding("A", 1, 750_000)))
        val plan = Rebalance.plan(b, emptyMap())
        assertEquals(7_500, plan.lines.first { it.code == "A" }.weightBp)
        assertEquals(2_500, plan.lines.last().weightBp)
    }

    @Test
    fun `목표 주식 수는 내림한다`() {
        // total = 1_000_000, 목표 50% = 500_000, 현재가 45_000 -> 11.11주 -> 11주
        val b = Balance(cash = 1_000_000, holdings = listOf(holding("A", 0, 45_000, evalAmt = 0)))
        val plan = Rebalance.plan(b, mapOf("A" to 5_000))
        assertEquals(11, plan.lines.first { it.code == "A" }.deltaShares)
    }

    @Test
    fun `목표가 없으면 델타는 null 이다`() {
        val b = Balance(cash = 0, holdings = listOf(holding("A", 3, 1_000)))
        assertNull(
            Rebalance
                .plan(b, emptyMap())
                .lines
                .first { it.code == "A" }
                .deltaShares,
        )
    }

    @Test
    fun `현재가가 0 이면 델타는 null 이다`() {
        val b = Balance(cash = 1_000_000, holdings = listOf(holding("A", 3, 0, evalAmt = 0)))
        assertNull(
            Rebalance
                .plan(b, mapOf("A" to 5_000))
                .lines
                .first { it.code == "A" }
                .deltaShares,
        )
    }

    @Test
    fun `매도는 음수 델타로 나온다`() {
        // total = 1_000_000, 목표 10% = 100_000, 현재가 10_000 -> 10주 보유 100주 -> -90
        val b = Balance(cash = 0, holdings = listOf(holding("A", 100, 10_000)))
        assertEquals(
            -90,
            Rebalance
                .plan(b, mapOf("A" to 1_000))
                .lines
                .first { it.code == "A" }
                .deltaShares,
        )
    }

    @Test
    fun `현금 행은 마지막이고 목표를 가질 수 있다`() {
        val b = Balance(cash = 500_000, holdings = listOf(holding("A", 1, 500_000)))
        val plan = Rebalance.plan(b, mapOf(Rebalance.CASH to 3_000))
        val last = plan.lines.last()
        assertEquals(Rebalance.CASH, last.code)
        assertEquals(3_000, last.targetBp)
        assertNull(last.deltaShares)
    }

    @Test
    fun `종목코드 CASH 인 보유는 현금 행이 아니다`() {
        val b = Balance(cash = 100, holdings = listOf(holding("CASH", 1, 900)))
        val plan = Rebalance.plan(b, mapOf("CASH" to 1_000))
        assertEquals(2, plan.lines.size)
        assertEquals("CASH", plan.lines[0].code)
        assertEquals(Rebalance.CASH, plan.lines[1].code)
        assertEquals(1_000, plan.lines[0].targetBp)
        assertNull(plan.lines[1].targetBp)
    }

    @Test
    fun `목표 합계와 매매 후 예수금을 계산한다`() {
        // total = 1_000_000. A 목표 60% -> 600_000 / 10_000 = 60주, 현재 0주 -> +60 -> 600_000 지출
        val b = Balance(cash = 1_000_000, holdings = listOf(holding("A", 0, 10_000, evalAmt = 0)))
        val plan = Rebalance.plan(b, mapOf("A" to 6_000, Rebalance.CASH to 4_000))
        assertEquals(10_000, plan.targetSumBp)
        assertEquals(400_000, plan.cashAfter)
    }

    @Test
    fun `예수금이 모자라면 매매 후 예수금이 음수다`() {
        val b = Balance(cash = 0, holdings = listOf(holding("A", 1, 1_000), holding("B", 1, 1_000)))
        // total = 2_000. A 목표 100% -> 2주, 현재 1주 -> +1 -> 1_000 지출, 예수금 0
        val plan = Rebalance.plan(b, mapOf("A" to 10_000))
        assertEquals(-1_000, plan.cashAfter)
    }

    @Test
    fun `빈 포트폴리오는 예외 없이 현금 100 퍼센트다`() {
        val plan = Rebalance.plan(Balance(cash = 10_000, holdings = emptyList()), emptyMap())
        assertEquals(1, plan.lines.size)
        assertEquals(10_000, plan.lines.single().weightBp)
    }

    @Test
    fun `모두 0 이면 비중도 0 이다`() {
        val plan = Rebalance.plan(Balance(cash = 0, holdings = emptyList()), emptyMap())
        assertEquals(0, plan.total)
        assertEquals(0, plan.lines.single().weightBp)
    }

    @Test
    fun `범위를 벗어난 목표 비중은 거부한다`() {
        val b = Balance(cash = 1_000, holdings = emptyList())
        assertFailsWith<IllegalArgumentException> { Rebalance.plan(b, mapOf("A" to -1)) }
        assertFailsWith<IllegalArgumentException> { Rebalance.plan(b, mapOf("A" to 10_001)) }
    }

    @Test
    fun `목표 합계가 100 퍼센트 이하면 매수 금액도 총자산 이하다`() {
        val rnd = Random(42)
        repeat(200) {
            val holdings =
                List(rnd.nextInt(1, 6)) { i ->
                    holding("C$i", rnd.nextLong(0, 100), rnd.nextLong(1, 100_000))
                }
            val cash = rnd.nextLong(0, 10_000_000)
            var left = 10_000
            val targets =
                holdings.associate { h ->
                    val bp = rnd.nextInt(0, left + 1).also { left -= it }
                    h.code to bp
                }
            val plan = Rebalance.plan(Balance(cash, holdings), targets)
            val bought =
                holdings.sumOf { h ->
                    val d = plan.lines.first { it.code == h.code }.deltaShares ?: 0L
                    (h.qty + d) * h.price
                }
            assert(bought <= plan.total) { "bought=$bought total=${plan.total}" }
        }
    }
}
