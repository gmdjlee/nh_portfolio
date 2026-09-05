package dev.nhportfolio

import dev.nhportfolio.market.Action
import dev.nhportfolio.market.Band
import dev.nhportfolio.market.Bar
import dev.nhportfolio.market.Breadth
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun bar(
    date: String,
    close: Int,
    refPrice: Int = close,
    exRight: Boolean = false,
) = Bar(date = date, close = close, refPrice = refPrice, exRight = exRight)

private fun labels(n: Int): List<String> = List(n) { "D%04d".format(it) }

/** 항상 값이 있고(0 없음) 양수인 랜덤워크 종가열 — MA·시장폭 계산이 워밍업 걱정 없이 도는 "깨끗한" 종목. */
private fun walk(
    days: Int,
    seed: Long,
): IntArray {
    val rnd = Random(seed)
    var price = 10_000
    return IntArray(days) {
        price = (price + rnd.nextInt(-200, 201)).coerceIn(5_000, 20_000)
        price
    }
}

private fun universe(
    stockCount: Int,
    days: Int,
    seed: Long,
): Map<String, IntArray> = List(stockCount) { i -> "S$i" to walk(days, seed * 1_000 + i) }.toMap()

class BreadthTest {
    // ---- 수정주가 보정 ----

    @Test
    fun `2대1 분할 이전 종가가 절반이 된다`() {
        val bars =
            listOf(
                bar("20260101", close = 19_000),
                bar("20260102", close = 20_000),
                bar("20260105", close = 10_100, refPrice = 10_000, exRight = true),
                bar("20260106", close = 10_200),
            )
        assertEquals(listOf(9_500, 10_000, 10_100, 10_200), Breadth.adjust(bars).toList())
    }

    @Test
    fun `응답이 이미 수정주가면 원본을 그대로 돌려준다`() {
        val bars =
            listOf(
                bar("20260101", close = 9_500),
                bar("20260102", close = 10_000),
                // factor = refPrice / 전일종가 = 10000 / 10000 = 1.0 — 이미 수정된 데이터라 저절로 통과한다
                bar("20260105", close = 10_100, refPrice = 10_000, exRight = true),
                bar("20260106", close = 10_200),
            )
        assertEquals(listOf(9_500, 10_000, 10_100, 10_200), Breadth.adjust(bars).toList())
    }

    @Test
    fun `exRight 가 아닌 날의 큰 가격 변동에는 손대지 않는다`() {
        val bars =
            listOf(
                bar("20260101", close = 10_000),
                bar("20260102", close = 20_000), // 폭등이지만 exRight=false 라 보정 대상이 아니다
            )
        assertEquals(listOf(10_000, 20_000), Breadth.adjust(bars).toList())
    }

    @Test
    fun `배당락만 있는 날은 exRight 가 false 라 보정하지 않는다`() {
        val bars =
            listOf(
                bar("20260101", close = 10_000),
                // 배당락 하락(약 -3%)이지만 액면분할·병합·권리락이 아니므로 exRight=false 다
                bar("20260102", close = 9_700, refPrice = 9_700),
            )
        assertEquals(listOf(10_000, 9_700), Breadth.adjust(bars).toList())
    }

    /**
     * 분모는 항상 그 시점의 **원본** 전일 종가여야 한다. 뒤쪽(더 최근) 분할이 이미 곱해
     * 넣은 값을 분모로 쓰면, 앞쪽(더 과거) 분할의 계수가 1.0 근처로 뭉개져 2% 문턱에
     * 걸려 보정이 조용히 건너뛰어진다 — 두 번 겹친 2:1 분할은 이 결함을 드러낸다.
     */
    @Test
    fun `2대1 분할이 두 번 겹쳐도 전부 같은 값으로 보정된다`() {
        val bars =
            listOf(
                bar("20260101", close = 40_000),
                bar("20260102", close = 40_000),
                bar("20260105", close = 20_000, refPrice = 20_000, exRight = true),
                bar("20260106", close = 20_000),
                bar("20260107", close = 10_000, refPrice = 10_000, exRight = true),
                bar("20260108", close = 10_000),
            )
        assertEquals(List(6) { 10_000 }, Breadth.adjust(bars).toList())
    }

    // ---- 이동평균 ----

    @Test
    fun `유효 관측이 200일선 최소치 미만인 종목은 그 날 분모에서 빠진다`() {
        val days = 500 // 9구성이 전부 정의되는 최소치(497)보다 넉넉히 위 — signal 이 null 이면 비교가 안 된다
        val clean = universe(stockCount = 30, days = days, seed = 7)
        // 마지막 50일만 값이 있고 나머지는 상장 전(0) — 200일 창의 유효 관측이 150(=floor(200*.75)) 미만이다.
        // 값을 우상향시켜 두어, 잘못 끼어들면(above 로 잡히면) 비율이 바뀌도록 한다.
        val short = IntArray(days) { t -> if (t < days - 50) 0 else 10_000 + (t - (days - 50)) * 200 }
        val withShort = clean + ("SHORT" to short)

        val a = assertNotNull(Breadth.signal(clean, labels(days)))
        val b = assertNotNull(Breadth.signal(withShort, labels(days)))
        assertEquals(a.breadth, b.breadth, "이력이 짧은 종목이 분모에 끼어들면 비율이 달라진다")
    }

    // ---- 시장폭 ----

    @Test
    fun `유효 종목이 30 미만인 날은 시장폭이 미정의라 신호가 null 이다`() {
        val days = 420
        val tiny = universe(stockCount = 10, days = days, seed = 11) // 종목 수 자체가 30 미만
        assertNull(Breadth.signal(tiny, labels(days)))
    }

    // ---- 백분위 (signal 을 통한 window 경계) ----

    /**
     * 9구성 중 가장 늦게 차는 것은 (ma=250, sm=60) 이다 — 250일선은 min_periods=187 이라
     * 186일째(0-idx)부터 시장폭이 정의되고, 백분위는 관측 252개가 더 쌓인 437일째부터,
     * sm=60 평활은 그로부터 59일 더 지난 496일째부터 정의된다. 즉 총 497일이면 9구성이
     * 전부 차고, 496일이면 하나(250,60)가 모자라 신호 전체가 null 이다. 이 경계는
     * `Breadth` 의 실제 상수(이동평균 150/200/250, 평활 20/40/60)에서 유도한 값이며,
     * 아래 assertNotNull/assertNull 로 양쪽을 직접 확인한다.
     */
    @Test
    fun `9구성이 전부 정의되는 최소 일수에서만 signal 이 non-null 이다`() {
        val stocks = universe(stockCount = 30, days = 497, seed = 21)
        assertNull(Breadth.signal(stocks.mapValues { it.value.copyOfRange(0, 496) }, labels(496)))
        assertNotNull(Breadth.signal(stocks, labels(497)))
    }

    @Test
    fun `관측 1000개면 최근 756개만 쓰고 window 가 756 이다`() {
        val days = 150 + 1_000 // MA200 워밍업 149일 + 관측 1000개 — 250일선도 넉넉히 756 을 채운다
        val stocks = universe(stockCount = 30, days = days, seed = 22)
        val signal = assertNotNull(Breadth.signal(stocks, labels(days)))
        assertEquals(756, signal.window)
    }

    // ---- 백분위 (pctRank 직접) ----

    @Test
    fun `pctRank 는 관측 251개면 미정의(NaN)고 252개면 값이 나온다`() {
        assertTrue(Breadth.pctRank(DoubleArray(251) { it.toDouble() }).last().isNaN())
        assertFalse(Breadth.pctRank(DoubleArray(252) { it.toDouble() }).last().isNaN())
    }

    @Test
    fun `riskmodel_functions 의 예시 - 창 5 를 걸면 마지막 값은 0점2 다`() {
        val values = doubleArrayOf(10.0, 20.0, 30.0, 40.0, 50.0, 5.0)
        assertEquals(0.2, Breadth.pctRank(values, win = 5, minPeriods = 1).last())
    }

    @Test
    fun `최솟값의 백분위는 1 나누기 n 이지 0 이 아니다`() {
        val values = doubleArrayOf(5.0, 4.0, 3.0, 2.0, 1.0)
        assertEquals(1.0 / 5, Breadth.pctRank(values, win = 5, minPeriods = 1).last())
    }

    @Test
    fun `동점은 평균 순위로 처리된다`() {
        assertEquals(2.5 / 3, Breadth.pctRank(doubleArrayOf(1.0, 2.0, 2.0), win = 3, minPeriods = 1).last())
        assertEquals(2.0 / 3, Breadth.pctRank(doubleArrayOf(2.0, 2.0, 2.0), win = 3, minPeriods = 1).last())
    }

    // ---- 반올림 ----

    @Test
    fun `양자화는 은행가 반올림 규칙을 따른다`() {
        assertEquals(0, Breadth.quantize(0.0624))
        assertEquals(0, Breadth.quantize(0.0625)) // 0.5 는 짝수 0 으로 — roundToInt 라면 1250 이 나와 틀린다
        assertEquals(1_250, Breadth.quantize(0.0626))
        assertEquals(2_500, Breadth.quantize(0.1875))
        assertEquals(2_500, Breadth.quantize(0.3125)) // 2.5 도 짝수 2 로 — roundToInt 라면 3750 이 나와 틀린다
    }

    // ---- 양자화 두 번 (앙상블) ----

    @Test
    fun `양자화는 두 번이다 - 구성별로 먼저 반올림한 뒤 평균해야 한다`() {
        val smooths = List(5) { 0.19 } + List(4) { 0.17 }
        // 오답: 평활값을 바로 평균해 한 번만 반올림하면 (5*0.19+4*0.17)/9 ≈ 0.1811 -> 1250 이 나온다.
        assertEquals(2_500, Breadth.quantize(smooths))
    }

    @Test
    fun `앙상블 - 구성 전부가 같은 백분위면 그 값을 양자화한 것이 목표다`() {
        assertEquals(1_250, Breadth.quantize(List(9) { 0.10 }))
        assertEquals(7_500, Breadth.quantize(List(9) { 0.80 }))
    }

    // ---- 밴드 ----

    @Test
    fun `밴드 경계값은 오른쪽(높은 쪽) 구간에 속한다`() {
        assertEquals(Band.MAX_DEFENSE, Breadth.bandOf(1_299))
        assertEquals(Band.DEFENSE, Breadth.bandOf(1_300))
        assertEquals(Band.DEFENSE, Breadth.bandOf(4_399))
        assertEquals(Band.NEUTRAL, Breadth.bandOf(4_400))
        assertEquals(Band.NEUTRAL, Breadth.bandOf(5_599))
        assertEquals(Band.ACTIVE, Breadth.bandOf(5_600))
        assertEquals(Band.ACTIVE, Breadth.bandOf(8_149))
        assertEquals(Band.MAX_INVEST, Breadth.bandOf(8_150))
    }

    // ---- 판정 ----

    @Test
    fun `차이가 1499bp 면 HOLD 고 1500bp 부터 방향에 따라 CUT 또는 ADD 다`() {
        val hold = Breadth.verdict(targetBp = 1_000, heldBp = 2_499)
        assertEquals(Action.HOLD, hold.action)
        assertEquals(-1_499, hold.gapBp)

        val cut = Breadth.verdict(targetBp = 1_000, heldBp = 2_500)
        assertEquals(Action.CUT, cut.action)
        assertEquals(-1_500, cut.gapBp)

        val add = Breadth.verdict(targetBp = 2_500, heldBp = 1_000)
        assertEquals(Action.ADD, add.action)
        assertEquals(1_500, add.gapBp)
    }

    @Test
    fun `유지 비중 2500 에 목표 1250 은 HOLD 고 목표 0 은 CUT 이다 (가이드북 2026-09-04 사례)`() {
        assertEquals(Action.HOLD, Breadth.verdict(targetBp = 1_250, heldBp = 2_500).action)
        assertEquals(Action.CUT, Breadth.verdict(targetBp = 0, heldBp = 2_500).action)
    }

    @Test
    fun `비중이 0 이거나 10000 인 극단에서도 예외가 나지 않는다`() {
        assertEquals(Action.CUT, Breadth.verdict(targetBp = 0, heldBp = 10_000).action)
        assertEquals(Action.ADD, Breadth.verdict(targetBp = 10_000, heldBp = 0).action)
    }

    // ---- 골든 벡터 ----

    /** 고정 시드로 만든 소형 유니버스의 목표 비중을 못 박아 둔다 — 이후 리팩터링이 결과를 바꾸면 이 테스트가 잡는다. */
    @Test
    fun `고정 시드 유니버스의 목표 비중은 회귀 방지용으로 고정된다`() {
        val days = 600
        val stocks = universe(stockCount = 35, days = days, seed = 99)
        val signal = assertNotNull(Breadth.signal(stocks, labels(days)))
        assertEquals(7_500, signal.targetBp)
    }
}
