package dev.nhportfolio

import dev.nhportfolio.market.Band
import dev.nhportfolio.market.Cause
import dev.nhportfolio.market.Check
import dev.nhportfolio.market.Fit
import dev.nhportfolio.market.Market
import dev.nhportfolio.market.Obs
import dev.nhportfolio.market.Report
import dev.nhportfolio.market.Track
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.pow
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val BASE_DATE: LocalDate = LocalDate.of(2020, 1, 1)

/**
 * 실제로 파싱 가능한 YYYYMMDD 달력을 만든다. [dev.nhportfolio.market.Track] 은 STALE 진단에서
 * `asOf`·`computedOn` 을 `java.time` 으로 파싱하므로, `BreadthTest` 의 "D0001" 식 가짜 라벨을
 * 쓰면 게이트가 통과하는 테스트에서 진단이 날짜를 파싱하다 예외를 던진다. 주말을 건너뛰지
 * 않아도 Track 은 `dates` 를 순서 있는 라벨로만 다루므로 계산 결과에는 영향이 없다.
 */
private fun labels(n: Int): List<String> = (0 until n).map { BASE_DATE.plusDays(it.toLong()).format(DateTimeFormatter.BASIC_ISO_DATE) }

private fun obsAt(
    dates: List<String>,
    pos: Int,
    targetBp: Int,
    score: Double = 0.5,
    window: Int = 756,
    universeAt: String = "U1",
    computedOn: String = dates[pos],
) = Obs(asOf = dates[pos], computedOn = computedOn, targetBp = targetBp, score = score, window = window, universeAt = universeAt)

/** t 가 짝수면 [up] 만큼 오르고 홀수면 [down] 만큼 내리는 결정적 톱니 경로. 무작위 없이 큰 변동성·낙폭을 재현한다. */
private fun sawtooth(
    days: Int,
    start: Double,
    up: Double,
    down: Double,
): DoubleArray {
    var p = start
    return DoubleArray(days) { t ->
        p *= if (t % 2 == 0) 1.0 + up else 1.0 - down
        p
    }
}

class TrackTest {
    // ---- equalWeight ----

    @Test
    fun `equalWeight 은 상장 전 0 을 분모에서 빼고 첫 유효일 전날을 1로 놓는다`() {
        val closes =
            mapOf(
                "A" to intArrayOf(100, 110, 121),
                // B 는 첫날 상장 전(0). 둘째 날은 전일이 0 이라 그 날 수익률도 계산되지 않는다.
                "B" to intArrayOf(0, 200, 220),
            )
        val out = Track.equalWeight(closes, 3)

        // t=1 이 첫 유효 수익률(A, +10%)이라 그 전날인 t=0 이 1.0 으로 소급되어 놓인다.
        assertEquals(1.0, out[0], 1e-12)
        assertEquals(1.0 * 1.10, out[1], 1e-12)
        // t=2: A(+10%), B(+10%) 둘 다 유효. 평균도 10%.
        assertEquals(out[1] * 1.10, out[2], 1e-12)
    }

    @Test
    fun `equalWeight 은 유효 수익률이 없는 날 이전 값을 유지한다`() {
        // t=2,3 은 상장폐지(둘 다 0)라 그 날은 유효한 수익률이 하나도 없다.
        val closes = mapOf("A" to intArrayOf(100, 110, 0, 0))
        val out = Track.equalWeight(closes, 4)

        assertEquals(1.10, out[1], 1e-12)
        assertEquals(out[1], out[2], 1e-12)
        assertEquals(out[1], out[3], 1e-12)
    }

    // ---- simulate ----

    @Test
    fun `simulate 는 3일짜리 손 계산과 비용까지 일치한다`() {
        // t=1 은 목표(0.25)가 그대로라 재조정이 없다. t=2 는 t=1 에서 정해진 목표(0.60)로 갈아타며
        // |0.60-0.25|=0.35 ge 0.15 이므로 매수 비용(0.0002)을 문다.
        val targetBp = intArrayOf(2_500, 6_000, -1)
        val prices = doubleArrayOf(100.0, 102.0, 101.0)

        val sim = Track.simulate(targetBp, prices)

        val ret1 = prices[1] / prices[0] - 1.0
        val nav1 = 1.0 * (1.0 + 0.25 * ret1)
        val ret2 = prices[2] / prices[1] - 1.0
        val nav2Precost = nav1 * (1.0 + 0.25 * ret2)
        val nav2 = nav2Precost * (1.0 - 0.35 * 0.0002)

        assertEquals(1.0, sim.nav[0])
        assertEquals(0.25, sim.held[0])
        assertEquals(nav1, sim.nav[1], 1e-12)
        assertEquals(0.25, sim.held[1])
        assertEquals(nav2, sim.nav[2], 1e-12)
        assertEquals(0.60, sim.held[2], 1e-12)
        assertEquals(1, sim.trades)
    }

    @Test
    fun `simulate 는 목표 차이가 15퍼센트포인트 미만이면 실행하지 않는다`() {
        // t=2 시점의 want=0.39(=targetBp[1]) 이고 held=0.25 라 차이는 0.14 — 15%p 미만이라 그대로 둔다.
        val targetBp = intArrayOf(2_500, 3_900, -1)
        val prices = doubleArrayOf(100.0, 105.0, 106.0)

        val sim = Track.simulate(targetBp, prices)

        val ret1 = prices[1] / prices[0] - 1.0
        val nav1 = 1.0 * (1.0 + 0.25 * ret1)
        val ret2 = prices[2] / prices[1] - 1.0
        val nav2 = nav1 * (1.0 + 0.25 * ret2)

        assertEquals(nav2, sim.nav[2], 1e-12)
        assertEquals(0.25, sim.held[2], 1e-12)
        assertEquals(0, sim.trades)
    }

    @Test
    fun `simulate 는 매도쪽 비용도 손 계산과 일치한다`() {
        // t=1 은 목표(0.8)가 그대로라 재조정이 없다. t=2 는 t=1 에서 정해진 목표(0.2)로 줄이며
        // |0.2-0.8|=0.6 ge 0.15 이므로 매도 비용(0.0017)을 문다.
        val targetBp = intArrayOf(8_000, 2_000, -1)
        val prices = doubleArrayOf(100.0, 102.0, 101.0)

        val sim = Track.simulate(targetBp, prices)

        val ret1 = prices[1] / prices[0] - 1.0
        val nav1 = 1.0 * (1.0 + 0.8 * ret1)
        val ret2 = prices[2] / prices[1] - 1.0
        val nav2Precost = nav1 * (1.0 + 0.8 * ret2)
        val nav2 = nav2Precost * (1.0 - 0.6 * 0.0017)

        assertEquals(0.8, sim.held[0])
        assertEquals(nav2, sim.nav[2], 1e-12)
        assertEquals(0.2, sim.held[2], 1e-12)
        assertEquals(1, sim.trades)
    }

    // ---- riskFit ----

    @Test
    fun `riskFit 은 낮은 밴드 뒤에 변동성 큰 구간이 있으면 MATCH 다`() {
        val crash = sawtooth(150, 1_000.0, up = 0.02, down = 0.08)
        val calm = sawtooth(150, crash.last(), up = 0.0005, down = 0.0004)
        val prices = crash + calm
        val dates = labels(300)
        // 낮은 밴드(MAX_DEFENSE) 뒤에는 폭락 구간이, 높은 밴드(MAX_INVEST) 뒤에는 잔잔한 구간이 온다.
        val lowBand = (0 until 10).map { i -> obsAt(dates, i, targetBp = 0) }
        val highBand = (0 until 10).map { i -> obsAt(dates, 150 + i, targetBp = 10_000) }

        val report = Track.report(lowBand + highBand, Market(dates, null, prices), emptyList(), null)

        assertEquals(Fit.MATCH, report.riskFit)
    }

    @Test
    fun `riskFit 은 뒤집으면 MISMATCH 다`() {
        val crash = sawtooth(150, 1_000.0, up = 0.02, down = 0.08)
        val calm = sawtooth(150, crash.last(), up = 0.0005, down = 0.0004)
        val prices = crash + calm
        val dates = labels(300)
        // 변동성 큰 구간 뒤에 높은 밴드를, 잔잔한 구간 뒤에 낮은 밴드를 붙인다 — 위 테스트를 뒤집는다.
        val highBand = (0 until 10).map { i -> obsAt(dates, i, targetBp = 10_000) }
        val lowBand = (0 until 10).map { i -> obsAt(dates, 150 + i, targetBp = 0) }

        val report = Track.report(highBand + lowBand, Market(dates, null, prices), emptyList(), null)

        assertEquals(Fit.MISMATCH, report.riskFit)
    }

    @Test
    fun `riskFit 은 표본 5 이상인 밴드가 2종 미만이면 UNKNOWN 이다`() {
        val crash = sawtooth(150, 1_000.0, up = 0.02, down = 0.08)
        val calm = sawtooth(150, crash.last(), up = 0.0005, down = 0.0004)
        val prices = crash + calm
        val dates = labels(300)
        val lowBand = (0 until 10).map { i -> obsAt(dates, i, targetBp = 0) }
        val highBand = (0 until 3).map { i -> obsAt(dates, 150 + i, targetBp = 10_000) } // 5건 미만 — 자격 미달

        val report = Track.report(lowBand + highBand, Market(dates, null, prices), emptyList(), null)

        assertEquals(Fit.UNKNOWN, report.riskFit)
    }

    @Test
    fun `BandStats 는 상수 일별 수익률 두 창에서 median worst drop10 rise10 vol 을 정확히 낸다`() {
        val n = 200
        val dates = labels(n)
        val prices = DoubleArray(n)
        for (t in 0..63) prices[t] = 1_000.0 * 1.002.pow(t) // 창1(관측 pos=0): 일별 +0.2%, fwd1 ≈ +13.4%
        prices[64] = 1_000.0 // 창2 는 독립된 기준가에서 시작한다
        for (t in 65..127) prices[t] = 1_000.0 * 0.997.pow(t - 64) // 창2(관측 pos=64): 일별 -0.3%, fwd2 ≈ -17.2%
        for (t in 128 until n) prices[t] = prices[127]

        val obs = listOf(obsAt(dates, 0, targetBp = 2_000), obsAt(dates, 64, targetBp = 2_000)) // 둘 다 DEFENSE
        val report = Track.report(obs, Market(dates, null, prices), emptyList(), null)

        val band = report.bands.single()
        assertEquals(Band.DEFENSE, band.band)
        assertEquals(2, band.n)

        val fwd1 = 1.002.pow(63) - 1.0
        val fwd2 = 0.997.pow(63) - 1.0
        val dailyReturns = List(63) { 0.002 } + List(63) { -0.003 }
        val mean = dailyReturns.average()
        val variance = dailyReturns.sumOf { (it - mean) * (it - mean) } / (dailyReturns.size - 1)
        val expectedVol = kotlin.math.sqrt(variance) * kotlin.math.sqrt(252.0)

        assertEquals((fwd1 + fwd2) / 2.0, band.median, 1e-9)
        assertEquals(minOf(fwd1, fwd2), band.worst, 1e-9)
        assertEquals(0.5, band.drop10, 1e-9) // fwd2 ≈ -17% < -10% 인 창이 둘 중 하나
        assertEquals(0.5, band.rise10, 1e-9) // fwd1 ≈ +13% > 10% 인 창이 둘 중 하나
        assertEquals(expectedVol, band.vol, 1e-9)
    }

    @Test
    fun `direction 은 NEUTRAL 을 빼고 ACTIVE 상승은 적중 DEFENSE 상승은 미적중으로 가른다`() {
        val n = 300
        val dates = labels(n)
        val prices = DoubleArray(n)
        for (t in 0..63) prices[t] = 1_000.0 * 1.002.pow(t) // ACTIVE 뒤 상승 -> 적중(밴드가 상승을 주장했고 실제로 올랐다)
        prices[64] = 1_000.0
        for (t in 65..127) prices[t] = 1_000.0 * 1.001.pow(t - 64) // DEFENSE 뒤에도 상승 -> 미적중(하락을 주장했는데 올랐다)
        prices[128] = 1_000.0
        for (t in 129..191) prices[t] = 1_000.0 * 0.999.pow(t - 128) // NEUTRAL -> 방향 주장이 없어 통계에서 아예 빠져야 한다
        for (t in 192 until n) prices[t] = prices[191]

        val obs =
            listOf(
                obsAt(dates, 0, targetBp = 6_000), // ACTIVE
                obsAt(dates, 64, targetBp = 2_000), // DEFENSE
                obsAt(dates, 128, targetBp = 5_000), // NEUTRAL
            )
        val report = Track.report(obs, Market(dates, null, prices), emptyList(), null)

        assertEquals(0.5, assertNotNull(report.direction), 1e-9)
    }

    // ---- Gate 경계 ----

    @Test
    fun `게이트는 성숙 관측 23건에서 미달이고 24건에서 충족된다`() {
        val dates = labels(300)

        fun build(k: Int) =
            Track.report(
                (0 until k).map { i -> obsAt(dates, i * 6, if (i % 2 == 0) 0 else 10_000) },
                Market(dates, null, DoubleArray(300) { 1.0 }),
                emptyList(),
                null,
            )

        assertFalse(build(23).gate.ok, "matured=23")
        assertTrue(build(24).gate.ok, "matured=24")
    }

    @Test
    fun `게이트는 첫 마지막 관측 간격 125거래일에서 미달이고 126거래일에서 충족된다`() {
        val dates = labels(300)

        fun build(lastPos: Int): Report {
            val cluster = (0..22).map { obsAt(dates, it, 5_000) }
            val last = obsAt(dates, lastPos, 0)
            return Track.report(cluster + last, Market(dates, null, DoubleArray(300) { 1.0 }), emptyList(), null)
        }

        assertFalse(build(125).gate.ok, "span=125")
        assertTrue(build(126).gate.ok, "span=126")
    }

    @Test
    fun `게이트는 밴드 1종에서 미달이고 2종에서 충족된다`() {
        val dates = labels(300)

        fun build(secondBand: Int): Report {
            val cluster = (0..22).map { obsAt(dates, it, 5_000) }
            val last = obsAt(dates, 130, secondBand)
            return Track.report(cluster + last, Market(dates, null, DoubleArray(300) { 1.0 }), emptyList(), null)
        }

        assertFalse(build(5_000).gate.ok, "bands=1")
        assertTrue(build(0).gate.ok, "bands=2")
    }

    // ---- rollingCorr ----

    @Test
    fun `rollingCorr 은 y=x 에서 +1 이다`() {
        val dates = labels(50)
        val x = DoubleArray(50) { it.toDouble() }

        val corr = Track.rollingCorr(x, x.copyOf(), dates)

        assertEquals(1.0, corr[Track.CORR_MIN_OBS], 1e-9)
    }

    @Test
    fun `rollingCorr 은 y=-x 에서 -1 이다`() {
        val dates = labels(50)
        val x = DoubleArray(50) { it.toDouble() }
        val y = DoubleArray(50) { -it.toDouble() }

        val corr = Track.rollingCorr(x, y, dates)

        assertEquals(-1.0, corr[Track.CORR_MIN_OBS], 1e-9)
    }

    @Test
    fun `rollingCorr 은 창 안 관측이 11개면 NaN 이다`() {
        val dates = labels(50)
        val x = DoubleArray(50) { it.toDouble() }

        val corr = Track.rollingCorr(x, x.copyOf(), dates)

        // 창은 [0, t] 이라 원소 개수는 t+1 이다 — 11개가 되는 자리는 t = CORR_MIN_OBS - 2.
        assertTrue(corr[Track.CORR_MIN_OBS - 2].isNaN(), "CORR_MIN_OBS=${Track.CORR_MIN_OBS} 미만인 11개는 NaN 이어야 한다")
    }

    /**
     * score·trailing·forward 세 값 모두 관측 순서(i)의 순수한 1차식이 되도록 값을 직접 박아 넣는다.
     * 63일 간격을 두고 떨어진 세 자리(pos-63·pos·pos+63)는 관측 13개(5일 간격) 전부에서 서로 겹치지
     * 않으므로(63/5 가 정수가 아니다) 각 관측의 trailing·forward 를 독립적으로 지정할 수 있다.
     * score 가 trailing 과는 완전한 1차 비례(+), forward 와는 완전한 1차 반비례(-) 관계이므로
     * 피어슨 상관은 정확히 +1.0 과 -1.0 이 나와야 한다 — 부호가 바뀌거나 선행/동행이 뒤바뀌면 이 값이 갈린다.
     */
    @Test
    fun `coincident 는 동행이 오르면 양, predictive 는 이후가 내리면 음이고 정의되지 않은 자리는 NaN 이다`() {
        val n = 400
        val dates = labels(n)
        val prices = DoubleArray(n) { 1_000.0 }
        val positions = List(13) { i -> 137 + 5 * i }
        val obs =
            positions.mapIndexed { i, pos ->
                val trailing = 0.05 + 0.01 * i // i 에 대해 증가
                val forward = 0.10 - 0.01 * i // i 에 대해 감소
                prices[pos - Track.HORIZON] = 1_000.0
                prices[pos] = 1_000.0 * (1.0 + trailing)
                prices[pos + Track.HORIZON] = prices[pos] * (1.0 + forward)
                obsAt(dates, pos, targetBp = 5_000, score = 0.10 + 0.05 * i)
            }

        val report = Track.report(obs, Market(dates, null, prices), emptyList(), null)

        val at = positions.last()
        assertEquals(1.0, report.coincident[at], 1e-9)
        assertEquals(-1.0, report.predictive[at], 1e-9)
        assertTrue(report.coincident[50].isNaN(), "관측이 없는 이른 날짜는 NaN 이어야 한다")
        assertTrue(report.predictive[50].isNaN(), "관측이 없는 이른 날짜는 NaN 이어야 한다")
    }

    // ---- lagPeak ----

    @Test
    fun `lagPeak 는 5일 밀린 계열에서 5 를 돌려준다`() {
        val rnd = Random(42)
        val r = DoubleArray(300) { rnd.nextDouble(-1.0, 1.0) }
        val dx = DoubleArray(300) { t -> if (t >= 5) r[t - 5] else Double.NaN }

        assertEquals(5, Track.lagPeak(dx, r))
    }

    @Test
    fun `lagPeak 는 겹치는 관측이 60개 미만이면 null 이다`() {
        val rnd = Random(7)
        val n = 40
        val r = DoubleArray(n) { rnd.nextDouble(-1.0, 1.0) }
        val dx = DoubleArray(n) { t -> if (t >= 3) r[t - 3] else Double.NaN }

        assertNull(Track.lagPeak(dx, r))
    }

    // ---- 폐기 조건(tripwires) ----

    @Test
    fun `메커니즘 고장 - 회전매매 비용이 하락장에서 지수보다 나쁜 12개월 수익률을 만들면 TRIPPED 다`() {
        val n = 400
        val dates = labels(n)
        // 완만하지만 꾸준한 하락 — 트레일링 12개월 수익률이 항상 -20% 아래다.
        val prices = DoubleArray(n) { t -> 1_000.0 * 0.998.pow(t) }
        // 곧바로 되돌리는 회전매매 한 번을 심어 영구적인 비용 손실을 남긴다. 그 손실 구간을 포함하는
        // 어떤 12개월 창이든 모델 수익률이 지수 수익률보다 정확히 비용만큼 낮아진다.
        val obs =
            listOf(
                obsAt(dates, 0, 10_000),
                obsAt(dates, 10, 0),
                obsAt(dates, 11, 10_000),
            )

        val report = Track.report(obs, Market(dates, null, prices), emptyList(), null)

        val strategy = assertNotNull(report.strategy)
        val mechanism = strategy.tripwires.first { it.name == "메커니즘 고장" }
        assertEquals(Check.TRIPPED, mechanism.check)
    }

    @Test
    fun `낙폭 초과 - 모의 최대낙폭이 30퍼센트를 넘으면 TRIPPED 다`() {
        val n = 300
        val dates = labels(n)
        val prices = DoubleArray(n) { t -> if (t <= 100) 1_000.0 - t * 6.0 else 400.0 } // -60% 낙폭 후 보합
        // Strategy 는 관측 2건 이상이 달력에 매핑되어야 계산된다 — 목표가 같은 관측을 하나 더 두어
        // 재조정 없이(순수 추종) 손 계산이 그대로 성립하게 한다.
        val obs = listOf(obsAt(dates, 0, 10_000), obsAt(dates, 1, 10_000))

        val report = Track.report(obs, Market(dates, null, prices), emptyList(), null)

        val strategy = assertNotNull(report.strategy)
        val dd = strategy.tripwires.first { it.name == "낙폭 초과" }
        assertEquals(Check.TRIPPED, dd.check)

        // 목표가 시작부터 끝까지 100%(재조정 없음)라 모의 NAV 가 가격을 1:1로 그대로 따라간다 —
        // ret·mdd 가 단순 보유(holdRet·holdMdd)와 정확히 같아야 한다. 1000 -> 400 은 -60%.
        assertEquals(-0.6, strategy.ret, 1e-9)
        assertEquals(-0.6, strategy.holdRet, 1e-9)
        assertEquals(-0.6, strategy.mdd, 1e-9)
        assertEquals(-0.6, strategy.holdMdd, 1e-9)
        // MATCH 는 모의 낙폭이 단순 보유보다 얕아야(엄격히 커야) 하는데 여기선 완전히 같고,
        // 게다가 낙폭 초과 폐기 조건도 TRIPPED 다 — 어느 쪽으로 봐도 MISMATCH 다.
        assertEquals(Fit.MISMATCH, report.strategyFit)
    }

    @Test
    fun `12개월 손실 초과 - 어느 시점이든 트레일링 12개월 수익률이 마이너스 20퍼센트 밑이면 TRIPPED 다`() {
        val n = 300
        val dates = labels(n)
        val prices = DoubleArray(n) { t -> 1_000.0 * 0.999.pow(t) } // 252일 누적 약 -22%
        val obs = listOf(obsAt(dates, 0, 10_000), obsAt(dates, 1, 10_000))

        val report = Track.report(obs, Market(dates, null, prices), emptyList(), null)

        val strategy = assertNotNull(report.strategy)
        val lossYear = strategy.tripwires.first { it.name == "12개월 손실 초과" }
        assertEquals(Check.TRIPPED, lossYear.check)
    }

    @Test
    fun `신호 노이즈화 - 최근 실행이 15회를 넘으면 TRIPPED 다`() {
        val n = 300
        val dates = labels(n)
        val prices = DoubleArray(n) { 1_000.0 }
        val base = obsAt(dates, 0, 10_000)
        // 0 과 10000 을 스무 번 오가며 매번 15%p 문턱을 넘겨 실행을 강제한다.
        val churn = (0 until 20).map { i -> obsAt(dates, 250 + i, if (i % 2 == 0) 0 else 10_000) }

        val report = Track.report(listOf(base) + churn, Market(dates, null, prices), emptyList(), null)

        val strategy = assertNotNull(report.strategy)
        val noise = strategy.tripwires.first { it.name == "신호 노이즈화" }
        assertEquals(Check.TRIPPED, noise.check)
    }

    @Test
    fun `시뮬레이션 기간이 252거래일 미만이면 앞 세 폐기 조건은 UNKNOWN 이다`() {
        val n = 100
        val dates = labels(n)
        val prices = DoubleArray(n) { 1_000.0 - it }
        val obs = listOf(obsAt(dates, 0, 10_000), obsAt(dates, 1, 10_000))

        val report = Track.report(obs, Market(dates, null, prices), emptyList(), null)

        val strategy = assertNotNull(report.strategy)
        val byName = strategy.tripwires.associateBy { it.name }
        assertEquals(Check.UNKNOWN, byName.getValue("메커니즘 고장").check)
        assertEquals(Check.UNKNOWN, byName.getValue("낙폭 초과").check)
        assertEquals(Check.UNKNOWN, byName.getValue("12개월 손실 초과").check)
    }

    @Test
    fun `신호 노이즈화는 기간이 짧고 실행이 15회 이하면 UNKNOWN 이다`() {
        val n = 100
        val dates = labels(n)
        val prices = DoubleArray(n) { 1_000.0 }
        val obs = listOf(obsAt(dates, 0, 10_000), obsAt(dates, 50, 0))

        val report = Track.report(obs, Market(dates, null, prices), emptyList(), null)

        val strategy = assertNotNull(report.strategy)
        val noise = strategy.tripwires.first { it.name == "신호 노이즈화" }
        assertEquals(Check.UNKNOWN, noise.check)
    }

    // ---- report() 통합: 게이트·진단·강건성 ----

    @Test
    fun `게이트 미통과면 진단 목록이 비어 있다`() {
        val dates = labels(200)
        val obs = listOf(obsAt(dates, 0, 5_000))

        val report = Track.report(obs, Market(dates, null, DoubleArray(200) { 1.0 }), emptyList(), null)

        assertFalse(report.gate.ok)
        assertTrue(report.diagnoses.isEmpty())
    }

    @Test
    fun `게이트 통과 시 아홉 개 진단 원인이 모두 채워진다`() {
        val dates = labels(300)
        val obs = (0 until 24).map { i -> obsAt(dates, i * 6, if (i % 2 == 0) 0 else 10_000) }

        val report = Track.report(obs, Market(dates, null, DoubleArray(300) { 1.0 }), emptyList(), null)

        assertTrue(report.gate.ok)
        assertEquals(Cause.values().toSet(), report.diagnoses.map { it.cause }.toSet())
        assertEquals(9, report.diagnoses.size)

        // 목표가 0/10000 을 매번 오가며 23번 바뀌고, 그 폭(1.0)이 항상 15%p 문턱을 넘어 23번
        // 그대로 실행된다 — 실행이 변경의 절반에 못 미치지 않으므로 SUPPRESSED 는 flagged 가 아니다.
        val suppressed = report.diagnoses.first { it.cause == Cause.SUPPRESSED }
        assertFalse(suppressed.flagged)
    }

    @Test
    fun `asOf 가 중복되면 마지막 관측이 이긴다`() {
        val dates = labels(100)
        val first = Obs(asOf = dates[0], computedOn = dates[0], targetBp = 0, score = 0.1, window = 100, universeAt = "U1")
        val second = Obs(asOf = dates[0], computedOn = dates[0], targetBp = 10_000, score = 0.9, window = 200, universeAt = "U2")

        val report = Track.report(listOf(first, second), Market(dates, null, DoubleArray(100) { 1.0 }), emptyList(), null)

        assertEquals(listOf(Band.MAX_INVEST), report.bands.map { it.band })
    }

    @Test
    fun `달력에 없는 asOf 와 범위를 벗어난 값은 버려진다`() {
        val dates = labels(100)
        val notInCalendar = Obs(asOf = "99999999", computedOn = "99999999", targetBp = 5_000, score = 0.5, window = 100, universeAt = "U")
        val bpOutOfRange = Obs(asOf = dates[1], computedOn = dates[1], targetBp = 10_001, score = 0.5, window = 100, universeAt = "U")
        val scoreOutOfRange = Obs(asOf = dates[2], computedOn = dates[2], targetBp = 5_000, score = 1.5, window = 100, universeAt = "U")
        val good = obsAt(dates, 0, 5_000)

        val report =
            Track.report(
                listOf(notInCalendar, bpOutOfRange, scoreOutOfRange, good),
                Market(dates, null, DoubleArray(100) { 1.0 }),
                emptyList(),
                null,
            )

        assertEquals(1, report.gate.matured)
    }

    // ---- 원인 진단: 문턱 양쪽(NARROW·STALE·SUPPRESSED) ----

    /**
     * 낮은 밴드(DEFENSE) 23건 + 다른 밴드 1건(밴드 2종·기간 126 충족용)으로 게이트를 통과시킨다.
     * index·equal 을 각각 [ri]·[re] 로 매일 일정하게 성장시켜 낮은 밴드 관측 23건 전부가 같은
     * (지수 수익률 − 동일가중 수익률) 값을 내도록 한다 — 평균을 낼 필요 없이 값 하나만 재현하면 된다.
     */
    private fun narrowScenario(
        ri: Double,
        re: Double,
    ): Report {
        val n = 300
        val dates = labels(n)
        val index = DoubleArray(n) { t -> 1_000.0 * (1.0 + ri).pow(t) }
        val equal = DoubleArray(n) { t -> 1_000.0 * (1.0 + re).pow(t) }
        val lowBand = (0..22).map { obsAt(dates, it, targetBp = 2_000) } // DEFENSE
        val other = obsAt(dates, 130, targetBp = 5_000) // NEUTRAL — 밴드 2종·기간 126 충족용
        return Track.report(lowBand + listOf(other), Market(dates, index, equal), emptyList(), null)
    }

    @Test
    fun `NARROW 진단은 지수-동일가중 격차가 3퍼센트포인트를 넘으면 flagged 다`() {
        val report = narrowScenario(ri = 0.0030, re = -0.0030) // 격차 약 38%p — 문턱을 크게 웃돈다
        val narrow = report.diagnoses.first { it.cause == Cause.NARROW }
        assertTrue(narrow.flagged)
    }

    @Test
    fun `NARROW 진단은 지수-동일가중 격차가 3퍼센트포인트 이하면 flagged 가 아니다`() {
        val report = narrowScenario(ri = 0.0001, re = 0.00005) // 격차 약 0.3%p — 문턱을 밑돈다
        val narrow = report.diagnoses.first { it.cause == Cause.NARROW }
        assertFalse(narrow.flagged)
    }

    /** 클러스터 23건(밴드 무관, computedOn 만 다름) + 다른 밴드 1건으로 게이트를 통과시킨다. */
    private fun staleScenario(staleCount: Int): Report {
        val n = 300
        val dates = labels(n)
        val cluster =
            (0..22).map { i ->
                val computedOn =
                    if (i < staleCount) {
                        LocalDate.parse(dates[i], DateTimeFormatter.BASIC_ISO_DATE).plusDays(8).format(DateTimeFormatter.BASIC_ISO_DATE)
                    } else {
                        dates[i]
                    }
                obsAt(dates, i, targetBp = 5_000, computedOn = computedOn)
            }
        val other = obsAt(dates, 130, targetBp = 0)
        return Track.report(cluster + listOf(other), Market(dates, null, DoubleArray(n) { 1.0 }), emptyList(), null)
    }

    @Test
    fun `STALE 진단은 오래된 관측 비율이 25퍼센트를 넘으면 flagged 다`() {
        val report = staleScenario(staleCount = 7) // 7/24 ≈ 29.2% > 25%
        val stale = report.diagnoses.first { it.cause == Cause.STALE }
        assertTrue(stale.flagged)
    }

    @Test
    fun `STALE 진단은 오래된 관측 비율이 25퍼센트 이하면 flagged 가 아니다`() {
        val report = staleScenario(staleCount = 6) // 6/24 = 25%, 초과가 아니라서 flagged 가 아니다(경계)
        val stale = report.diagnoses.first { it.cause == Cause.STALE }
        assertFalse(stale.flagged)
    }

    @Test
    fun `SUPPRESSED 진단은 목표 변경 대비 모의 실행이 절반 미만이면 flagged 다`() {
        val n = 300
        val dates = labels(n)
        // 클러스터 안에서는 100bp 씩만 오가 15%p 문턱을 넘지 못해 실행이 없고, 마지막에만 크게 뛴다.
        val cluster = (0..22).map { i -> obsAt(dates, i, targetBp = if (i % 2 == 0) 5_000 else 5_100) }
        val jump = obsAt(dates, 130, targetBp = 0)

        val report = Track.report(cluster + listOf(jump), Market(dates, null, DoubleArray(n) { 1.0 }), emptyList(), null)

        // changes=23(클러스터 내 22번 + 마지막 점프 1번), trades=1(점프만 15%p 문턱을 넘는다) -> 1 < 11.5.
        val suppressed = report.diagnoses.first { it.cause == Cause.SUPPRESSED }
        assertTrue(suppressed.flagged)
    }
}
