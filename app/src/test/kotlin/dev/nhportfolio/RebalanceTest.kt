package dev.nhportfolio

import dev.nhportfolio.model.Balance
import dev.nhportfolio.model.Holding
import dev.nhportfolio.portfolio.Rebalance
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
            assertTrue(bought <= plan.total, "bought=$bought total=${plan.total}")
        }
    }

    // ---- 평가손익 ----

    @Test
    fun `평가손익은 평가금액에서 매입원가를 뺀 값이다`() {
        // 10주 × 평균 1,000 = 원가 10,000, 평가 12,000 -> +2,000 (+20%)
        val b = Balance(cash = 0, holdings = listOf(holding("A", 10, 1_200, evalAmt = 12_000).copy(avgPrice = 1_000)))
        val plan = Rebalance.plan(b, emptyMap())

        assertEquals(2_000, plan.totalPl)
        assertEquals(20.0, plan.totalPlRate)
    }

    @Test
    fun `현금은 손익 계산에 들어가지 않는다`() {
        // 현금을 원가에 넣으면 수익률이 희석된다 — 같은 종목이면 현금이 얼마든 수익률은 같아야 한다.
        val h = listOf(holding("A", 10, 1_200, evalAmt = 12_000).copy(avgPrice = 1_000))
        val poor = Rebalance.plan(Balance(cash = 0, holdings = h), emptyMap())
        val rich = Rebalance.plan(Balance(cash = 90_000_000, holdings = h), emptyMap())

        assertEquals(poor.totalPl, rich.totalPl)
        assertEquals(poor.totalPlRate, rich.totalPlRate)
    }

    @Test
    fun `손실이면 음수로 나온다`() {
        val b = Balance(cash = 0, holdings = listOf(holding("A", 10, 800, evalAmt = 8_000).copy(avgPrice = 1_000)))
        val plan = Rebalance.plan(b, emptyMap())

        assertEquals(-2_000, plan.totalPl)
        assertEquals(-20.0, plan.totalPlRate)
    }

    @Test
    fun `원가가 0 이면 수익률은 0 이다`() {
        // 0 으로 나누면 안 된다 — 무상주처럼 원가가 없는 경우가 실제로 있다.
        val b = Balance(cash = 1_000, holdings = listOf(holding("A", 10, 500, evalAmt = 5_000).copy(avgPrice = 0)))
        val plan = Rebalance.plan(b, emptyMap())

        assertEquals(5_000, plan.totalPl)
        assertEquals(0.0, plan.totalPlRate)
    }

    // ---- foldCash ----

    @Test
    fun `현금성 자산은 예수금에 합쳐지고 목록에서 빠진다`() {
        val b =
            Balance(
                cash = 100_000,
                holdings = listOf(holding("005930", 10, 70_000), holding("CMA01", 1, 500_000)),
            )

        val folded = Rebalance.foldCash(b, setOf("CMA01"))

        assertEquals(600_000, folded.cash)
        assertEquals(listOf("005930"), folded.holdings.map { it.code })
    }

    @Test
    fun `현금성으로 묶으면 매매 계획에서도 빠진다`() {
        val b =
            Balance(
                cash = 0,
                holdings = listOf(holding("A", 10, 10_000), holding("CMA01", 1, 900_000)),
            )
        // 총자산 100만. CMA 를 묶으면 현금 90만 + 주식 10만.
        val plan = Rebalance.plan(Rebalance.foldCash(b, setOf("CMA01")), mapOf("A" to 5_000))

        assertNull(plan.lines.firstOrNull { it.code == "CMA01" }, "현금성 자산이 종목 행으로 남았다")
        assertEquals(900_000, plan.lines.last().currentAmt)
        // 목표 50% = 50만 / 1만 = 50주, 현재 10주 -> +40
        assertEquals(40, plan.lines.first { it.code == "A" }.deltaShares)
    }

    @Test
    fun `현금성 자산의 비중은 현금 행에 반영된다`() {
        val b = Balance(cash = 0, holdings = listOf(holding("A", 1, 500_000), holding("CMA01", 1, 500_000)))

        val plan = Rebalance.plan(Rebalance.foldCash(b, setOf("CMA01")), emptyMap())

        assertEquals(5_000, plan.lines.last().weightBp)
    }

    @Test
    fun `지정이 없거나 해당 종목이 없으면 잔고가 그대로다`() {
        val b = Balance(cash = 100, holdings = listOf(holding("A", 1, 900)))

        assertEquals(b, Rebalance.foldCash(b, emptySet()))
        assertEquals(b, Rebalance.foldCash(b, setOf("없는코드")))
    }

    @Test
    fun `여러 현금성 자산이 모두 합쳐진다`() {
        val b =
            Balance(
                cash = 1_000,
                holdings = listOf(holding("A", 1, 100), holding("CMA", 1, 2_000), holding("MMF", 1, 3_000)),
            )

        val folded = Rebalance.foldCash(b, setOf("CMA", "MMF"))

        assertEquals(6_000, folded.cash)
        assertEquals(listOf("A"), folded.holdings.map { it.code })
    }

    // ---- scaleForCash ----

    @Test
    fun `예수금 목표를 잡으면 종목 합이 정확히 나머지가 된다`() {
        val out = Rebalance.scaleForCash(mapOf("A" to 4000, "B" to 3000, "C" to 3000), cashBp = 1000)

        assertEquals(mapOf("A" to 3600, "B" to 2700, "C" to 2700, Rebalance.CASH to 1000), out)
        assertEquals(10_000, out.values.sum())
    }

    @Test
    fun `종목끼리의 상대 비율은 유지된다`() {
        // 6000 -> 3000 은 정확히 절반이라 2:1 이 정수로 떨어진다
        val out = Rebalance.scaleForCash(mapOf("A" to 4000, "B" to 2000), cashBp = 7000)

        assertEquals(2000, out.getValue("A"))
        assertEquals(1000, out.getValue("B"))
        assertEquals(2 * out.getValue("B"), out.getValue("A"))
    }

    @Test
    fun `정수로 떨어지지 않는 비율은 1bp 안에서만 어긋난다`() {
        // 4000:2000 을 5000 에 담으면 3333.33:1666.67 — 정수 bp 로는 정확할 수 없다.
        // 합을 정확히 맞추는 쪽이 우선이고, 비율 오차는 1bp 를 넘지 않아야 한다.
        val out = Rebalance.scaleForCash(mapOf("A" to 4000, "B" to 2000), cashBp = 5000)

        assertEquals(5000, out.getValue("A") + out.getValue("B"))
        assertTrue(abs(out.getValue("A") - 2 * out.getValue("B")) <= 1, "비율 오차가 1bp 를 넘었다: $out")
    }

    @Test
    fun `종목 합이 모자라면 늘리기도 한다`() {
        // 70% 만 배분된 상태에서 예수금 10% -> 종목이 90% 를 채워야 하므로 증가한다
        val out = Rebalance.scaleForCash(mapOf("A" to 4000, "B" to 3000), cashBp = 1000)

        assertEquals(9000, out.getValue("A") + out.getValue("B"))
        assertTrue(out.getValue("A") > 4000, "줄어들기만 하면 '증가/감소' 요구를 못 지킨다: $out")
    }

    @Test
    fun `반올림 잔여분이 배분되어 합이 어긋나지 않는다`() {
        // 3등분은 정수 bp 로 나누어떨어지지 않는다 — 3333+3333+3333 = 9999 가 되면 안 된다
        val out = Rebalance.scaleForCash(mapOf("A" to 1, "B" to 1, "C" to 1), cashBp = 0)

        assertEquals(10_000, out.values.sum() - out.getValue(Rebalance.CASH))
        assertEquals(listOf(3333, 3333, 3334), out.filterKeys { it != Rebalance.CASH }.values.sorted())
    }

    @Test
    fun `예수금을 거듭 바꿔도 누적 오차가 없다`() {
        val start = mapOf("A" to 5000, "B" to 3000, "C" to 2000)

        val once = Rebalance.scaleForCash(start, cashBp = 2000)
        val twice = Rebalance.scaleForCash(Rebalance.scaleForCash(start, cashBp = 1000), cashBp = 2000)

        // 10% 를 거쳐 20% 로 가든 곧장 20% 로 가든 결과가 같아야 한다
        assertEquals(once, twice)
        assertEquals(8000, twice.filterKeys { it != Rebalance.CASH }.values.sum())
    }

    @Test
    fun `예수금 100 퍼센트면 전 종목이 0 이 된다`() {
        val out = Rebalance.scaleForCash(mapOf("A" to 6000, "B" to 4000), cashBp = 10_000)

        assertEquals(0, out.getValue("A"))
        assertEquals(0, out.getValue("B"))
        assertEquals(10_000, out.getValue(Rebalance.CASH))
    }

    @Test
    fun `종목 목표가 없으면 예수금만 설정한다`() {
        assertEquals(mapOf(Rebalance.CASH to 3000), Rebalance.scaleForCash(emptyMap(), cashBp = 3000))
        // 목표가 전부 0 이어도 나눌 기준이 없으므로 건드리지 않는다
        assertEquals(
            mapOf("A" to 0, Rebalance.CASH to 3000),
            Rebalance.scaleForCash(mapOf("A" to 0), cashBp = 3000),
        )
    }

    @Test
    fun `목표를 하나도 안 잡아도 현재 비중을 기준으로 조정안이 나온다`() {
        // 이게 없으면 예수금 목표만 넣었을 때 종목 행에 아무 변화가 없다.
        val out = Rebalance.scaleForCash(emptyMap(), cashBp = 1000, currentWeightsBp = mapOf("A" to 5000, "B" to 5000))

        assertEquals(4500, out.getValue("A"))
        assertEquals(4500, out.getValue("B"))
        assertEquals(1000, out.getValue(Rebalance.CASH))
        assertEquals(10_000, out.values.sum())
    }

    @Test
    fun `명시한 목표가 현재 비중을 이긴다`() {
        val out =
            Rebalance.scaleForCash(
                targetsBp = mapOf("A" to 8000),
                cashBp = 0,
                currentWeightsBp = mapOf("A" to 1000, "B" to 2000),
            )

        // A 는 지정한 8000, B 는 현재 비중 2000 을 기준으로 -> 8:2 비율로 10000 을 채운다
        assertEquals(8000, out.getValue("A"))
        assertEquals(2000, out.getValue("B"))
    }

    @Test
    fun `현재 비중이 0 인 종목은 목표도 0 이다`() {
        val out = Rebalance.scaleForCash(emptyMap(), cashBp = 0, currentWeightsBp = mapOf("A" to 10_000, "B" to 0))

        assertEquals(10_000, out.getValue("A"))
        assertEquals(0, out.getValue("B"))
    }

    @Test
    fun `보유도 목표도 없으면 예수금만 설정한다`() {
        assertEquals(mapOf(Rebalance.CASH to 3000), Rebalance.scaleForCash(emptyMap(), cashBp = 3000))
    }

    // ---- normalize ----

    @Test
    fun `합계가 모자라면 100 퍼센트로 늘린다`() {
        val out = Rebalance.normalize(mapOf("A" to 4000, "B" to 2000))

        assertEquals(10_000, out.values.sum())
        // 2:1 을 10000 에 담으면 6666.67:3333.33 — 합을 정확히 맞추는 쪽이 우선이라 1bp 가 남는다
        assertTrue(abs(out.getValue("A") - 2 * out.getValue("B")) <= 1, "비율 오차가 1bp 를 넘었다: $out")
    }

    @Test
    fun `합계가 넘치면 100 퍼센트로 줄인다`() {
        val out = Rebalance.normalize(mapOf("A" to 8000, "B" to 8000))

        assertEquals(10_000, out.values.sum())
        assertEquals(5000, out.getValue("A"))
        assertEquals(5000, out.getValue("B"))
    }

    @Test
    fun `예수금 목표는 그대로 두고 종목만 맞춘다`() {
        // 사용자가 일부러 정한 현금 비중을 말없이 바꾸면 안 된다
        val out = Rebalance.normalize(mapOf("A" to 4000, "B" to 2000, Rebalance.CASH to 2500))

        assertEquals(2500, out.getValue(Rebalance.CASH))
        assertEquals(7500, out.getValue("A") + out.getValue("B"))
        assertEquals(10_000, out.values.sum())
    }

    @Test
    fun `예수금 목표가 없으면 종목만으로 100 퍼센트를 채운다`() {
        val out = Rebalance.normalize(mapOf("A" to 1000))

        assertEquals(10_000, out.getValue("A"))
        assertFalse(Rebalance.CASH in out, "없던 예수금 목표를 만들어내면 안 된다: $out")
    }

    @Test
    fun `목표가 없어도 현재 비중으로 100 퍼센트를 만든다`() {
        val out = Rebalance.normalize(emptyMap(), currentWeightsBp = mapOf("A" to 3000, "B" to 1000))

        assertEquals(7500, out.getValue("A"))
        assertEquals(2500, out.getValue("B"))
    }

    @Test
    fun `이미 100 퍼센트면 그대로 둔다`() {
        val start = mapOf("A" to 6000, "B" to 3000, Rebalance.CASH to 1000)
        assertEquals(start, Rebalance.normalize(start))
    }

    @Test
    fun `무작위 입력에서도 정규화 결과는 정확히 100 퍼센트다`() {
        val rnd = Random(11)
        repeat(200) {
            val stocks = List(rnd.nextInt(1, 8)) { i -> "C$i" to rnd.nextInt(0, 4000) }.toMap()
            val targets = if (rnd.nextBoolean()) stocks + (Rebalance.CASH to rnd.nextInt(0, 5000)) else stocks
            if (stocks.values.sum() > 0) {
                val out = Rebalance.normalize(targets)
                assertEquals(10_000, out.values.sum(), "targets=$targets -> $out")
                assertTrue(out.values.all { it >= 0 }, "음수 비중: $out")
            }
        }
    }

    @Test
    fun `범위 밖 예수금 목표는 거부한다`() {
        assertFailsWith<IllegalArgumentException> { Rebalance.scaleForCash(mapOf("A" to 100), 10_001) }
        assertFailsWith<IllegalArgumentException> { Rebalance.scaleForCash(mapOf("A" to 100), -1) }
    }

    @Test
    fun `조정 결과를 plan 에 넣으면 목표 합계가 100 퍼센트가 된다`() {
        val holdings = listOf(holding("A", 10, 1000), holding("B", 10, 2000))
        val balance = Balance(cash = 5_000, holdings = holdings)

        val targets = Rebalance.scaleForCash(mapOf("A" to 7000, "B" to 3000), cashBp = 1500)
        val plan = Rebalance.plan(balance, targets)

        assertEquals(10_000, plan.targetSumBp)
    }

    @Test
    fun `무작위 입력에서도 합은 언제나 정확하다`() {
        val rnd = Random(7)
        repeat(200) {
            val stocks = List(rnd.nextInt(1, 8)) { i -> "C$i" to rnd.nextInt(0, 5000) }.toMap()
            val cashBp = rnd.nextInt(0, 10_001)
            val out = Rebalance.scaleForCash(stocks, cashBp)

            val stockSum = out.filterKeys { it != Rebalance.CASH }.values.sum()
            if (stocks.values.sum() > 0) {
                assertEquals(10_000 - cashBp, stockSum, "cashBp=$cashBp stocks=$stocks -> $out")
            }
            assertTrue(out.values.all { it >= 0 }, "음수 비중이 나왔다: $out")
        }
    }
}
