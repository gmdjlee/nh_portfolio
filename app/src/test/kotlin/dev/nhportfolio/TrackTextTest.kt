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
import dev.nhportfolio.market.diagnosisText
import dev.nhportfolio.market.directionText
import dev.nhportfolio.market.gateText
import dev.nhportfolio.market.label
import dev.nhportfolio.market.pct0
import dev.nhportfolio.market.retroObs
import dev.nhportfolio.market.retroScores
import dev.nhportfolio.market.riskFitText
import dev.nhportfolio.market.strategyFitText
import dev.nhportfolio.market.tripwireText
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

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
}
