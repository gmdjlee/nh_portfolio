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
}
