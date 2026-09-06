package dev.nhportfolio

import dev.nhportfolio.market.Band
import dev.nhportfolio.market.BandStats
import dev.nhportfolio.market.Breadth
import dev.nhportfolio.market.Cause
import dev.nhportfolio.market.Check
import dev.nhportfolio.market.Diagnosis
import dev.nhportfolio.market.Fit
import dev.nhportfolio.market.Gate
import dev.nhportfolio.market.Obs
import dev.nhportfolio.market.Report
import dev.nhportfolio.market.Signal
import dev.nhportfolio.market.Strategy
import dev.nhportfolio.market.Track
import dev.nhportfolio.market.Tripwire
import dev.nhportfolio.market.dateTickCandidates
import dev.nhportfolio.market.dateTicks
import dev.nhportfolio.market.diagnosisText
import dev.nhportfolio.market.directionText
import dev.nhportfolio.market.gateText
import dev.nhportfolio.market.label
import dev.nhportfolio.market.niceTicks
import dev.nhportfolio.market.pct0
import dev.nhportfolio.market.retroObs
import dev.nhportfolio.market.retroScores
import dev.nhportfolio.market.riskFitText
import dev.nhportfolio.market.strategyFitText
import dev.nhportfolio.market.tickLabel
import dev.nhportfolio.market.tripwireText
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun signal(
    targetBp: Int,
    score: Double = 0.5,
    window: Int = Breadth.PCT_WIN,
    asOf: String = "20260101",
) = Signal(targetBp = targetBp, band = Breadth.bandOf(targetBp), breadth = 0.0, pctile = 0.0, window = window, asOf = asOf, score = score)

private fun bandStats(
    band: Band,
    n: Int,
    vol: Double = 0.2,
    drop10: Double = 0.1,
    rise10: Double = 0.1,
    worst: Double = -0.2,
) = BandStats(band = band, n = n, vol = vol, drop10 = drop10, rise10 = rise10, worst = worst, median = 0.0)

private fun strategy(
    mdd: Double = -0.1,
    holdMdd: Double = -0.1,
    tripwires: List<Tripwire> = emptyList(),
) = Strategy(ret = 0.0, mdd = mdd, holdRet = 0.0, holdMdd = holdMdd, trades = 0, tripwires = tripwires)

private fun tripwire(check: Check) = Tripwire(name = "이름", check = check, detail = "상세")

private fun report(
    gate: Gate = Gate(matured = 0, spanDays = 0, bands = 0),
    riskFit: Fit = Fit.UNKNOWN,
    strategyFit: Fit = Fit.UNKNOWN,
    direction: Double? = null,
    bands: List<BandStats> = emptyList(),
    strategy: Strategy? = null,
) = Report(
    gate = gate,
    riskFit = riskFit,
    strategyFit = strategyFit,
    direction = direction,
    bands = bands,
    strategy = strategy,
    coincident = DoubleArray(0),
    predictive = DoubleArray(0),
    diagnoses = emptyList(),
)

/** 주말만 뺀 평일 달력을 yyyyMMdd 오름차순으로 만든다 — dateTicks 테스트용 합성 데이터. */
private fun weekdays(
    start: LocalDate,
    end: LocalDate,
): List<String> {
    val out = mutableListOf<String>()
    var d = start
    while (!d.isAfter(end)) {
        if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) {
            out.add(d.format(DateTimeFormatter.BASIC_ISO_DATE))
        }
        d = d.plusDays(1)
    }
    return out
}

/** niceTicks 가 실제로 지키기로 한 계약: 눈금 사이 간격이 전부 같아야 한다(2.5×10^k 걸음이 소수 자리 하나를 빼먹으면 깨진다). */
private fun assertEvenSpacing(ticks: List<Double>) {
    if (ticks.size < 2) return
    val step = ticks[1] - ticks[0]
    for (i in 2 until ticks.size) {
        assertTrue(abs((ticks[i] - ticks[i - 1]) - step) < 1e-9, "간격이 고르지 않다: $ticks")
    }
}

/** dateTicks 가 실제로 지키기로 한 계약: 이웃 눈금을 [plotWidthPx] 위에 늘어놨을 때 간격이 [minGapPx] 이상이어야 한다. */
private fun assertGapsHold(
    ticks: List<Pair<Int, String>>,
    dateCount: Int,
    plotWidthPx: Float,
    minGapPx: Float,
) {
    val denom = (dateCount - 1).toFloat()
    val xs = ticks.map { (i, _) -> i / denom * plotWidthPx }
    for (k in 1 until xs.size) {
        assertTrue(xs[k] - xs[k - 1] >= minGapPx, "gap ${xs[k] - xs[k - 1]} < $minGapPx at $k")
    }
}

class TrackTextTest {
    // ---- pct0 ----

    @Test
    fun `양수는 그대로 퍼센트로 보인다`() {
        assertEquals("28%", pct0(0.28))
    }

    @Test
    fun `음수는 U+2212 로 보인다`() {
        assertEquals("−9%", pct0(-0.09))
    }

    @Test
    fun `NaN 은 하이픈이다`() {
        assertEquals("-", pct0(Double.NaN))
    }

    @Test
    fun `절반은 은행가 반올림으로 짝수 쪽에 붙는다`() {
        // 0.125·0.625 는 100 을 곱하면 이진수로도 정확히 12.5·62.5 다(둘 다 1/8 단위라 오차가
        // 없다). kotlin.math.round 는 짝수 쪽(12, 62)으로 붙지만, roundToInt(반올림 올림)였다면
        // 13%·63%가 나와 이 값들에서 깨진다.
        assertEquals("12%", pct0(0.125))
        assertEquals("62%", pct0(0.625))
    }

    // ---- riskFitText ----

    @Test
    fun `위험 분리가 맞으면 서수가 가장 낮은 밴드와 가장 높은 밴드의 변동성을 보여준다`() {
        val r =
            report(
                riskFit = Fit.MATCH,
                bands = listOf(bandStats(Band.MAX_DEFENSE, n = 10, vol = 0.28), bandStats(Band.ACTIVE, n = 6, vol = 0.17)),
            )

        assertEquals("위험 분리: 맞음(최대 방어 변동성 28% > 적극 17%)", riskFitText(r))
    }

    @Test
    fun `위험 분리가 어긋나면 변동성과 하락 확률을 둘 다 vs 로 나란히 보여준다`() {
        val r =
            report(
                riskFit = Fit.MISMATCH,
                bands =
                    listOf(
                        bandStats(Band.MAX_DEFENSE, n = 10, vol = 0.10, drop10 = 0.05),
                        bandStats(Band.ACTIVE, n = 6, vol = 0.30, drop10 = 0.08),
                    ),
            )

        assertEquals("위험 분리: 어긋남(최대 방어 변동성 10% vs 적극 30%, −10% 확률 5% vs 8%)", riskFitText(r))
    }

    @Test
    fun `표본 5건 미만인 밴드는 저울에서 빠진다`() {
        // DEFENSE 는 n=3(표본 부족)이라 빠지고 MAX_DEFENSE·ACTIVE 만 남아야 한다 — 셋 중 하나라도
        // 저울에 끼면 30%17% 대신 DEFENSE 의 99%가 섞여 나온다.
        val r =
            report(
                riskFit = Fit.MATCH,
                bands =
                    listOf(
                        bandStats(Band.MAX_DEFENSE, n = 10, vol = 0.30),
                        bandStats(Band.DEFENSE, n = 3, vol = 0.99),
                        bandStats(Band.ACTIVE, n = 6, vol = 0.17),
                    ),
            )

        assertEquals("위험 분리: 맞음(최대 방어 변동성 30% > 적극 17%)", riskFitText(r))
    }

    @Test
    fun `가운데 밴드가 아무리 극단이어도 서수 최저 최고만 고른다`() {
        val r =
            report(
                riskFit = Fit.MATCH,
                bands =
                    listOf(
                        bandStats(Band.MAX_DEFENSE, n = 10, vol = 0.10),
                        bandStats(Band.NEUTRAL, n = 10, vol = 0.50),
                        bandStats(Band.MAX_INVEST, n = 10, vol = 0.05),
                    ),
            )

        assertEquals("위험 분리: 맞음(최대 방어 변동성 10% > 최대 투입 5%)", riskFitText(r))
    }

    @Test
    fun `저울에 오를 밴드가 둘 미만이면 판정 불가다`() {
        val r = report(riskFit = Fit.MATCH, bands = listOf(bandStats(Band.MAX_DEFENSE, n = 10, vol = 0.30)))

        assertEquals("위험 분리: 판정 불가(표본 ${Track.MIN_PER_BAND}건 이상인 밴드가 2개 미만)", riskFitText(r))
    }

    @Test
    fun `밴드가 둘 이상이어도 riskFit 이 UNKNOWN 이면 판정 불가다`() {
        val r =
            report(
                riskFit = Fit.UNKNOWN,
                bands = listOf(bandStats(Band.MAX_DEFENSE, n = 10, vol = 0.30), bandStats(Band.ACTIVE, n = 6, vol = 0.17)),
            )

        assertEquals("위험 분리: 판정 불가(표본 ${Track.MIN_PER_BAND}건 이상인 밴드가 2개 미만)", riskFitText(r))
    }

    // ---- strategyFitText ----

    @Test
    fun `전략 가치가 맞으면 낙폭 비교와 폐기 조건 개수를 보여준다`() {
        val tripwires = listOf(tripwire(Check.TRIPPED), tripwire(Check.OK), tripwire(Check.OK), tripwire(Check.UNKNOWN))
        val r = report(strategyFit = Fit.MATCH, strategy = strategy(mdd = -0.09, holdMdd = -0.16, tripwires = tripwires))

        assertEquals("전략 가치: 맞음 · 최대낙폭 −9% vs 단순 보유 −16%, 폐기 조건 1/4", strategyFitText(r))
    }

    @Test
    fun `전략 가치가 어긋나도 맞음만 어긋남으로 바뀐다`() {
        val tripwires = listOf(tripwire(Check.TRIPPED), tripwire(Check.TRIPPED), tripwire(Check.OK), tripwire(Check.OK))
        val r = report(strategyFit = Fit.MISMATCH, strategy = strategy(mdd = -0.09, holdMdd = -0.16, tripwires = tripwires))

        assertEquals("전략 가치: 어긋남 · 최대낙폭 −9% vs 단순 보유 −16%, 폐기 조건 2/4", strategyFitText(r))
    }

    @Test
    fun `모의 실행이 없으면 관측 부족으로 판정 불가다`() {
        val r = report(strategyFit = Fit.MATCH, strategy = null)

        assertEquals("전략 가치: 판정 불가(관측 부족)", strategyFitText(r))
    }

    // ---- directionText ----

    @Test
    fun `방향 적중은 모델의 주장이 아니라는 문구를 항상 붙인다`() {
        assertEquals("방향 적중 54% (모델의 주장이 아님)", directionText(0.54))
    }

    @Test
    fun `중립 밖 관측이 없으면 방향 적중도 판정 불가다`() {
        assertEquals("방향 적중: 판정 불가(중립 밖 관측 없음) (모델의 주장이 아님)", directionText(null))
    }

    // ---- gateText ----

    @Test
    fun `게이트 진행은 Track 상수를 분모로 보여준다`() {
        val text = gateText(Gate(matured = 10, spanDays = 50, bands = 1))

        assertEquals(
            "원인 분석까지: 성숙 관측 10/${Track.MIN_MATURED} · 기간 50/${Track.MIN_SPAN}일 · 밴드 1/${Track.MIN_BANDS}",
            text,
        )
    }

    // ---- tripwireText ----

    @Test
    fun `TRIPPED 는 해당이다`() {
        assertEquals("이름: 해당 · 상세", tripwireText(tripwire(Check.TRIPPED)))
    }

    @Test
    fun `OK 는 미해당이다`() {
        assertEquals("이름: 미해당 · 상세", tripwireText(tripwire(Check.OK)))
    }

    @Test
    fun `UNKNOWN 은 판정 불가다`() {
        assertEquals("이름: 판정 불가 · 상세", tripwireText(tripwire(Check.UNKNOWN)))
    }

    // ---- diagnosisText ----

    @Test
    fun `관측 기록 모드는 원인 문구를 그대로 보여준다`() {
        val d = Diagnosis(cause = Cause.STALE, flagged = true, text = "계산 지연 문구")

        assertEquals("계산 지연 문구", diagnosisText(d, retro = false))
    }

    @Test
    fun `소급 모드의 STALE 은 계산일이 관측일과 같다는 사유가 붙는다`() {
        val d = Diagnosis(cause = Cause.STALE, flagged = false, text = "계산 지연 문구")

        assertEquals("계산 지연 문구 (소급 계산은 계산일이 관측일과 같아 해당 없음)", diagnosisText(d, retro = true))
    }

    @Test
    fun `소급 모드의 UNIVERSE 는 현재 유니버스로 계산했다는 사유가 붙는다`() {
        val d = Diagnosis(cause = Cause.UNIVERSE, flagged = false, text = "유니버스 교체 문구")

        assertEquals("유니버스 교체 문구 (소급 계산은 현재 유니버스 한 벌로 계산해 해당 없음)", diagnosisText(d, retro = true))
    }

    @Test
    fun `소급 모드라도 STALE UNIVERSE 가 아니면 문구가 그대로다`() {
        val d = Diagnosis(cause = Cause.NARROW, flagged = true, text = "지수 쏠림 문구")

        assertEquals("지수 쏠림 문구", diagnosisText(d, retro = true))
    }

    // ---- Cause.label ----

    @Test
    fun `Cause 라벨은 사양 3절 표의 이름과 같다`() {
        assertEquals("지수 쏠림", Cause.NARROW.label())
        assertEquals("시차", Cause.LAG.label())
        assertEquals("부분 창", Cause.PARTIAL.label())
        assertEquals("경계 잡음", Cause.BOUNDARY.label())
        assertEquals("실행 억제", Cause.SUPPRESSED.label())
        assertEquals("미적용", Cause.NOT_APPLIED.label())
        assertEquals("유니버스 교체", Cause.UNIVERSE.label())
        assertEquals("계산 지연", Cause.STALE.label())
        assertEquals("표본 부족", Cause.SAMPLE.label())
    }

    // ---- retroObs ----

    @Test
    fun `신호가 있는 날짜만 관측으로 바뀌고 asOf 와 computedOn 이 dates 값과 같다`() {
        // signal 의 asOf 는 일부러 "무시됨" 으로 채운다 — retroObs 가 그 값이 아니라 dates[i] 를
        // 써야 한다는 함정(브리프 2)을 검증한다.
        val dates = listOf("20260101", "20260102", "20260103")
        val retro = listOf(signal(targetBp = 5000, score = 0.4, window = 700, asOf = "무시됨"), null, signal(targetBp = 7500, score = 0.6))

        val obs = retroObs(dates, retro, universeAt = "20260228")

        assertEquals(2, obs.size)
        assertEquals(
            Obs(asOf = "20260101", computedOn = "20260101", targetBp = 5000, score = 0.4, window = 700, universeAt = "20260228"),
            obs[0],
        )
        assertEquals("20260103", obs[1].asOf)
        assertEquals("20260103", obs[1].computedOn)
    }

    @Test
    fun `신호가 전부 없으면 관측도 빈 목록이다`() {
        val obs = retroObs(listOf("20260101"), listOf(null), universeAt = "20260228")

        assertEquals(emptyList(), obs)
    }

    // ---- retroScores ----

    @Test
    fun `신호가 없는 날은 NaN 점수다`() {
        val retro = listOf(signal(targetBp = 5000, score = 0.3), null, signal(targetBp = 7500, score = 0.7))

        val scores = retroScores(retro)

        assertContentEquals(doubleArrayOf(0.3, Double.NaN, 0.7), scores)
    }

    // ---- niceTicks ----

    @Test
    fun `95_3 에서 141_2 까지는 10 단위 다섯 눈금이다`() {
        val ticks = niceTicks(95.3, 141.2)

        assertContentEquals(listOf(100.0, 110.0, 120.0, 130.0, 140.0), ticks)
        assertEvenSpacing(ticks)
    }

    @Test
    fun `0 에서 1 까지는 0_2 단위 여섯 눈금이다`() {
        val ticks = niceTicks(0.0, 1.0)

        assertContentEquals(listOf(0.0, 0.2, 0.4, 0.6, 0.8, 1.0), ticks)
        assertEvenSpacing(ticks)
    }

    @Test
    fun `92 에서 106_3 까지는 2_5 단위 여섯 눈금이고 92_5 는 93 으로 뭉개지지 않는다`() {
        // 회귀: roundToStep 이 걸음 값만으로 소수 자릿수를 되짚으면(-floor(log10(step))) 2.5 걸음이
        // 한 자리 모자라(0 자리) 92.5 가 93 으로 반올림됐다 — factor·exponent 를 따로 들고 다녀야 한다.
        val ticks = niceTicks(92.0, 106.3)

        assertContentEquals(listOf(92.5, 95.0, 97.5, 100.0, 102.5, 105.0), ticks)
        assertEvenSpacing(ticks)
    }

    @Test
    fun `0 에서 1_3 까지는 0_25 단위 여섯 눈금이다`() {
        val ticks = niceTicks(0.0, 1.3)

        assertContentEquals(listOf(0.0, 0.25, 0.5, 0.75, 1.0, 1.25), ticks)
        assertEvenSpacing(ticks)
    }

    @Test
    fun `범위가 좁으면 값은 범위 안에서 오름차순으로 1개에서 6개 사이만 나오고 간격은 고르다`() {
        val ticks = niceTicks(99.5, 100.5)

        assertTrue(ticks.size in 1..6)
        assertTrue(ticks.all { it in 99.5..100.5 })
        assertContentEquals(ticks.sorted(), ticks)
        assertEvenSpacing(ticks)
    }

    @Test
    fun `lo 가 hi 이상이면 빈 목록이다`() {
        assertContentEquals(emptyList(), niceTicks(5.0, 5.0))
        assertContentEquals(emptyList(), niceTicks(6.0, 5.0))
    }

    @Test
    fun `NaN 이 섞이면 빈 목록이다`() {
        assertContentEquals(emptyList(), niceTicks(Double.NaN, 1.0))
        assertContentEquals(emptyList(), niceTicks(0.0, Double.NaN))
    }

    // ---- tickLabel ----

    @Test
    fun `걸음이 요구하는 소수 자릿수만큼만 보여주고 정수 걸음은 소수점을 안 붙인다`() {
        assertEquals("99.4", tickLabel(99.4, 0.2))
        assertEquals("100.0", tickLabel(100.0, 0.2))
        assertEquals("120", tickLabel(120.0, 10.0))
        assertEquals("2.5", tickLabel(2.5, 2.5))
    }

    // ---- dateTickCandidates ----

    @Test
    fun `3년 달력에서 후보는 월 36 분기 12 연 3 개다`() {
        val dates = weekdays(LocalDate.of(2023, 1, 2), LocalDate.of(2025, 12, 31))

        val candidates = dateTickCandidates(dates)

        assertEquals(36, candidates.month.size)
        assertEquals(12, candidates.quarter.size)
        assertEquals(3, candidates.year.size)
    }

    // ---- dateTicks ----

    @Test
    fun `좁은 그림 폭에서는 연도 눈금만 남는다`() {
        // 3년치 평일 달력 — 월간 8개월(20일 안팎) 간격도 300px 에서는 64px 를 못 채운다.
        val dates = weekdays(LocalDate.of(2023, 1, 2), LocalDate.of(2025, 12, 31))

        val ticks = dateTicks(dates, plotWidthPx = 300f, minGapPx = 64f)

        assertEquals(listOf("2023", "2024", "2025"), ticks.map { it.second })
        assertGapsHold(ticks, dates.size, plotWidthPx = 300f, minGapPx = 64f)
    }

    @Test
    fun `넓은 그림 폭에서는 분기 눈금 12개가 나온다`() {
        // 같은 3년 달력을 2000px 로 넓히면 월(약 20일 간격)은 여전히 64px 를 못 채우지만
        // 분기(약 64일 간격)는 채운다 — 그래서 연이 아니라 분기가 뽑힌다.
        val dates = weekdays(LocalDate.of(2023, 1, 2), LocalDate.of(2025, 12, 31))

        val ticks = dateTicks(dates, plotWidthPx = 2000f, minGapPx = 64f)

        assertEquals(
            listOf("23.01", "23.04", "23.07", "23.10", "24.01", "24.04", "24.07", "24.10", "25.01", "25.04", "25.07", "25.10"),
            ticks.map { it.second },
        )
        ticks.forEach { (i, _) -> assertTrue(i == 0 || dates[i].substring(0, 6) != dates[i - 1].substring(0, 6)) }
        assertGapsHold(ticks, dates.size, plotWidthPx = 2000f, minGapPx = 64f)
    }

    @Test
    fun `8개월 달력을 1000px 에 그리면 월별 눈금이다`() {
        val dates = weekdays(LocalDate.of(2023, 1, 2), LocalDate.of(2023, 8, 31))

        val ticks = dateTicks(dates, plotWidthPx = 1000f, minGapPx = 64f)

        assertEquals(
            listOf("23.01", "23.02", "23.03", "23.04", "23.05", "23.06", "23.07", "23.08"),
            ticks.map { it.second },
        )
        assertGapsHold(ticks, dates.size, plotWidthPx = 1000f, minGapPx = 64f)
    }

    @Test
    fun `날짜가 하나뿐이면 빈 목록이다`() {
        assertEquals(emptyList(), dateTicks(listOf("20260101"), plotWidthPx = 1000f, minGapPx = 64f))
    }

    @Test
    fun `연 눈금도 촘촘하면 몇 개씩 걸러 간격을 맞춘다`() {
        // 60년치 평일 달력을 300px 에 그리면 연 눈금(60개)조차 64px 를 못 채운다 — 걸러야 한다.
        val dates = weekdays(LocalDate.of(1970, 1, 2), LocalDate.of(2029, 12, 31))

        val ticks = dateTicks(dates, plotWidthPx = 300f, minGapPx = 64f)

        assertTrue(ticks.size in 2..59)
        assertGapsHold(ticks, dates.size, plotWidthPx = 300f, minGapPx = 64f)
    }
}
