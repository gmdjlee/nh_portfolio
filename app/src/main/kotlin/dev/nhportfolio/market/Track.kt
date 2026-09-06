package dev.nhportfolio.market

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt

/** 관측 한 건. `track.json` 의 행을 옮긴 시장 무관 타입이다. */
data class Obs(
    val asOf: String,
    val computedOn: String,
    val targetBp: Int,
    val score: Double,
    val window: Int,
    val universeAt: String,
)

/** 달력에 정렬된 시장 시계열. [index] 는 069500 정규화 종가(없으면 null), [equal] 은 동일가중 누적(첫날 1.0). */
data class Market(
    val dates: List<String>,
    val index: DoubleArray?,
    val equal: DoubleArray,
)

/** "이 목표로 맞추기" 를 누른 기록 한 줄. */
data class Applied(
    val date: String,
    val exposureBp: Int,
)

/**
 * 원인 분석(§3)이 계산되는 데 필요한 최소 데이터 조건. [ok] 는 세 조건을 모두 채웠는지다.
 * 판정 A(riskFit)·B(strategyFit)는 이 게이트와 무관하게 각자의 표본 조건으로 따로 판단한다 —
 * 게이트는 오직 [Report.diagnoses] 를 채울지만 결정한다.
 */
data class Gate(
    val matured: Int,
    val spanDays: Int,
    val bands: Int,
) {
    val ok: Boolean = matured >= Track.MIN_MATURED && spanDays >= Track.MIN_SPAN && bands >= Track.MIN_BANDS
}

/** 밴드 하나의 실측 통계. [n] 이 [Track.MIN_PER_BAND] 미만이면 표시용일 뿐 판정([Track.riskFit])에서는 빠진다. */
data class BandStats(
    val band: Band,
    val n: Int,
    val vol: Double,
    val drop10: Double,
    val rise10: Double,
    val worst: Double,
    val median: Double,
)

enum class Check { OK, TRIPPED, UNKNOWN }

/** 가이드북 7절의 폐기 조건 한 줄. [detail] 은 판정 근거가 된 값을 담은 한국어 문장이다. */
data class Tripwire(
    val name: String,
    val check: Check,
    val detail: String,
)

/**
 * 모의 실행 결과 요약. [ret]·[mdd] 는 모의 NAV, [holdRet]·[holdMdd] 는 같은 구간의 단순 보유(069500)
 * 기준값이다 — 화면은 이 넷을 나란히 보여준다.
 */
data class Strategy(
    val ret: Double,
    val mdd: Double,
    val holdRet: Double,
    val holdMdd: Double,
    val trades: Int,
    val tripwires: List<Tripwire>,
)

enum class Cause { NARROW, LAG, PARTIAL, BOUNDARY, SUPPRESSED, NOT_APPLIED, UNIVERSE, STALE, SAMPLE }

/** 원인 하나의 판정. [text] 는 계량값을 담은 완결된 한국어 문장이다. */
data class Diagnosis(
    val cause: Cause,
    val flagged: Boolean,
    val text: String,
)

enum class Fit { MATCH, MISMATCH, UNKNOWN }

data class Report(
    val gate: Gate,
    val riskFit: Fit,
    val strategyFit: Fit,
    val direction: Double?,
    val bands: List<BandStats>,
    val strategy: Strategy?,
    val coincident: DoubleArray,
    val predictive: DoubleArray,
    val diagnoses: List<Diagnosis>,
)

/** [Track.simulate] 의 결과. [prices] 와 길이가 같다. 첫 목표를 알기 전 날은 NaN — 모르는 구간을 0 으로 메우지 않는다. */
internal data class Sim(
    val nav: DoubleArray,
    val held: DoubleArray,
    val trades: Int,
)

/** 관측 한 건 + 시장 달력 위치. `Track` 내부 계산 전반에서 쓰는 공통 재료다. */
private data class Located(
    val obs: Obs,
    val pos: Int,
)

/** 성숙(미래 63일 종가가 있는) 관측 중 시장가로 이후 수익률까지 낼 수 있는 것. */
private data class Matured(
    val located: Located,
    val band: Band,
    val fwd: Double,
)

private const val FULL_BP = 10_000
private const val REBALANCE_BAND = 0.15
private const val SELL_COST = 0.0017
private const val BUY_COST = 0.0002
private const val TRADING_YEAR = 252
private const val TRIPWIRE_MIN_DAYS = 252
private const val NOISE_MAX_TRADES = 15
private const val APPLY_WINDOW = 5
private const val LAG_MIN_PAIRS = 60
private const val NARROW_THRESHOLD = 0.03
private const val LAG_THRESHOLD = 5
private const val PARTIAL_THRESHOLD = 0.5
private const val BOUNDARY_THRESHOLD = 0.5
private const val BOUNDARY_TOLERANCE = 1.0 / 32
private const val STALE_DAYS = 7L
private const val STALE_THRESHOLD = 0.25
private val DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE

/**
 * 시장 신호 효용성 평가. 관측 기록과 시장 대용 시계열을 받아 "잘 맞는가"(위험 분리·전략
 * 가치·방향 적중)와 그 원인을 계산하는 순수 함수 모음이다 — 네트워크도 안드로이드도 모른다.
 * `Breadth` 와 같은 격리 규칙(라이브러리 import 금지, 반올림은 [kotlin.math.round] 만)을 따른다.
 *
 * 사양 §5 는 "원인 분석과 판정 A·B는 게이트를 만족할 때만 계산한다"고 적지만, §6 화면
 * 구성은 판정 카드(A·B·C)를 항상 보여주고 "원인 분석"만 게이트 뒤에 접는다. 이 파일은 후자를
 * 따른다 — [riskFit]·[strategyFit] 은 각자의 표본 조건(밴드별 n, 관측 2건)으로 독립적으로
 * 판단하고, [Gate.ok] 는 오직 [Report.diagnoses] 를 채울지만 결정한다.
 *
 * 공개 API 만 이 객체 안에 두고, 나머지 계산은 이 파일의 최상위 private 함수로 뺐다 —
 * 객체 하나에 모든 것을 담으면 detekt `LargeClass` 에 걸릴 만큼 커지기 때문이다. 파일은
 * 여전히 하나이고 책임도 "관측+시장 → Report" 하나다.
 */
object Track {
    const val HORIZON = 63
    const val MIN_MATURED = 24
    const val MIN_SPAN = 126
    const val MIN_BANDS = 2
    const val MIN_PER_BAND = 5
    const val CORR_WIN = 126
    const val CORR_MIN_OBS = 12

    /**
     * 종목군의 동일가중 누적 지수. 날짜 [t] 의 수익률은 그 날과 전날 종가가 둘 다 0 이 아닌
     * 종목들(상장 전은 0 이라 자동으로 빠진다)의 평균이다. 첫 유효 수익률이 나온 날의 전날을
     * 1.0 으로 놓고 그 뒤로 복리 누적한다 — 그 전은 정의할 수 없으므로 NaN 이다. 유효 수익률이
     * 없는 날(전 종목이 상장 전이거나 상장폐지)은 이전 값을 그대로 이어간다.
     */
    fun equalWeight(
        closes: Map<String, IntArray>,
        days: Int,
    ): DoubleArray {
        val out = DoubleArray(days) { Double.NaN }
        var started = false
        for (t in 1 until days) {
            var sum = 0.0
            var count = 0
            for (series in closes.values) {
                val prev = series[t - 1]
                val cur = series[t]
                if (prev != 0 && cur != 0) {
                    sum += cur.toDouble() / prev - 1.0
                    count++
                }
            }
            if (count == 0) {
                if (started) out[t] = out[t - 1]
                continue
            }
            if (!started) {
                out[t - 1] = 1.0
                started = true
            }
            out[t] = out[t - 1] * (1.0 + sum / count)
        }
        return out
    }

    /**
     * 관측·시장·적용 기록을 하나의 [Report] 로 묶는다. 관측은 `asOf` 로 중복 제거(마지막 승)하고
     * 정렬한 뒤, 달력에 없거나 값이 범위를 벗어난 행은 버린다 — 모르는 값을 지어내지 않는다는
     * 원칙을 입력 단계에서부터 지킨다.
     */
    fun report(
        obs: List<Obs>,
        market: Market,
        applied: List<Applied>,
        retroScore: DoubleArray?,
    ): Report {
        val dates = market.dates
        val prices = market.index ?: market.equal
        val locatedAll = locate(obs, dates)
        val maturedList = locatedAll.filter { it.pos + HORIZON < dates.size }
        val pricedMaturedList = pricedMatured(maturedList, prices)

        val gateResult = gate(maturedList)
        val stats = bandStats(pricedMaturedList, prices)
        val sim = simulateFromObs(locatedAll, dates.size, prices)

        val strat = strategy(locatedAll, prices, sim)
        val diagnosisList =
            if (gateResult.ok) {
                diagnoses(maturedList, locatedAll, stats, market, prices, retroScore, applied, sim)
            } else {
                emptyList()
            }

        return Report(
            gate = gateResult,
            riskFit = riskFit(stats),
            strategyFit = strategyFit(strat),
            direction = direction(pricedMaturedList),
            bands = stats,
            strategy = strat,
            coincident = coincidentCorr(locatedAll, prices, dates),
            predictive = predictiveCorr(pricedMaturedList, dates),
            diagnoses = diagnosisList,
        )
    }

    /**
     * `riskmodel.backtest()` 의 이식. [targetBp] 는 날짜별 목표(-1 은 미정 — 마지막 목표를
     * 이어간다). 가격이 NaN 인 날은 그 날 수익률만 0 으로 본다(재조정 판단은 그대로 진행한다).
     * 첫 목표를 알기 전은 시뮬레이션 대상이 아니라 NaN 이다.
     */
    internal fun simulate(
        targetBp: IntArray,
        prices: DoubleArray,
    ): Sim {
        val n = prices.size
        val nav = DoubleArray(n) { Double.NaN }
        val heldPath = DoubleArray(n) { Double.NaN }
        val p0 = targetBp.indexOfFirst { it >= 0 }
        if (p0 < 0) return Sim(nav, heldPath, 0)

        val effective = IntArray(n)
        var last = targetBp[p0]
        for (t in p0 until n) {
            if (targetBp[t] >= 0) last = targetBp[t]
            effective[t] = last
        }

        nav[p0] = 1.0
        var held = effective[p0] / FULL_BP.toDouble()
        heldPath[p0] = held
        var trades = 0
        for (t in p0 + 1 until n) {
            val ret = dayReturn(prices, t)
            var v = nav[t - 1] * (1.0 + held * ret)
            val want = effective[t - 1] / FULL_BP.toDouble()
            val gap = want - held
            if (abs(gap) >= REBALANCE_BAND) {
                v *= 1.0 - abs(gap) * (if (want < held) SELL_COST else BUY_COST)
                held = want
                trades++
            }
            nav[t] = v
            heldPath[t] = held
        }
        return Sim(nav, heldPath, trades)
    }

    /** [x]·[y] 는 [dates] 에 정렬된 값(정의되지 않은 자리는 NaN). 날짜 t 마다 [CORR_WIN] 창 안의 유효쌍으로 피어슨 상관을 낸다. */
    internal fun rollingCorr(
        x: DoubleArray,
        y: DoubleArray,
        dates: List<String>,
    ): DoubleArray =
        DoubleArray(dates.size) { t ->
            val from = maxOf(0, t - CORR_WIN + 1)
            val xs = mutableListOf<Double>()
            val ys = mutableListOf<Double>()
            for (i in from..t) {
                if (!x[i].isNaN() && !y[i].isNaN()) {
                    xs.add(x[i])
                    ys.add(y[i])
                }
            }
            if (xs.size < CORR_MIN_OBS) Double.NaN else pearson(xs.toDoubleArray(), ys.toDoubleArray())
        }

    /**
     * [dx](신호 변화)와 [r](시장 수익률)의 교차상관이 최대가 되는 시차. 양수는 시장이 신호를
     * 앞선다는 뜻이다. 겹치는 유효쌍이 [LAG_MIN_PAIRS] 미만인 시차는 후보에서 뺀다 — 표본이
     * 모자란 시차가 우연히 최댓값을 내는 것을 막는다. 그런 시차가 하나도 없으면 null 이다.
     */
    internal fun lagPeak(
        dx: DoubleArray,
        r: DoubleArray,
        maxLag: Int = 60,
    ): Int? {
        var bestLag: Int? = null
        var bestCorr = Double.NEGATIVE_INFINITY
        for (d in 0..maxLag) {
            val candidates = if (d == 0) listOf(0) else listOf(-d, d)
            for (k in candidates) {
                val (xs, ys) = pairsAtLag(dx, r, k)
                if (xs.size < LAG_MIN_PAIRS) continue
                val c = pearson(xs, ys)
                if (!c.isNaN() && c > bestCorr) {
                    bestCorr = c
                    bestLag = k
                }
            }
        }
        return bestLag
    }
}

// ---------------------------------------------------------------- 공용 산술 헬퍼

/** [from]~[to] 구간의 누적 수익률. 인덱스가 배열을 벗어나거나 둘 중 하나라도 NaN 이면 NaN — 있는 척 지어내지 않는다. */
private fun windowReturn(
    series: DoubleArray,
    from: Int,
    to: Int,
): Double {
    if (from < 0 || to >= series.size) return Double.NaN
    val a = series[from]
    val b = series[to]
    return if (a.isNaN() || b.isNaN()) Double.NaN else b / a - 1.0
}

/** 가격이 NaN 인 날(둘 중 하나라도)은 그 날 수익률을 0 으로 본다 — "비어 있음" 을 손실로 지어내지 않는다. */
private fun dayReturn(
    prices: DoubleArray,
    t: Int,
): Double {
    val r = windowReturn(prices, t - 1, t)
    return if (r.isNaN()) 0.0 else r
}

private fun dailyReturn(series: DoubleArray): DoubleArray =
    DoubleArray(series.size) { t ->
        if (t ==
            0
        ) {
            Double.NaN
        } else {
            windowReturn(series, t - 1, t)
        }
    }

private fun firstDiff(series: DoubleArray): DoubleArray =
    DoubleArray(series.size) { t ->
        if (t == 0) {
            Double.NaN
        } else {
            val a = series[t - 1]
            val b = series[t]
            if (a.isNaN() || b.isNaN()) Double.NaN else b - a
        }
    }

private fun pearson(
    xs: DoubleArray,
    ys: DoubleArray,
): Double {
    val mx = xs.average()
    val my = ys.average()
    var sxy = 0.0
    var sxx = 0.0
    var syy = 0.0
    for (i in xs.indices) {
        val dx = xs[i] - mx
        val dy = ys[i] - my
        sxy += dx * dy
        sxx += dx * dx
        syy += dy * dy
    }
    return if (sxx == 0.0 || syy == 0.0) Double.NaN else sxy / sqrt(sxx * syy)
}

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
}

/** 표본표준편차(n-1). 점이 2개 미만이면 정의할 수 없으므로 NaN — 0 으로 지어내지 않는다. */
private fun sampleStdDev(values: List<Double>): Double {
    if (values.size < 2) return Double.NaN
    val mean = values.average()
    val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
    return sqrt(variance)
}

// ---------------------------------------------------------------- 문구 포맷(진단 전용)

/** x(0.042 같은 비율)를 "4.2" 처럼 부호를 보존한 퍼센트 포인트 한 자리 소수 문자열로. 반올림은 [round] 만 쓴다. */
private fun onePct(x: Double): String {
    val tenths = round(x * 1_000.0).toInt()
    val sign = if (tenths < 0) "-" else ""
    val magnitude = abs(tenths)
    return "$sign${magnitude / 10}.${magnitude % 10}"
}

private fun wholePct(x: Double): Int = round(x * 100.0).toInt()

// ---------------------------------------------------------------- 정렬·성숙

/** 중복 제거(마지막 승)·범위 검증·달력 매핑·위치 정렬까지 한 번에 한다. */
private fun locate(
    obs: List<Obs>,
    dates: List<String>,
): List<Located> {
    val posOf = dates.withIndex().associate { (i, d) -> d to i }
    return obs
        .filter { it.score in 0.0..1.0 && it.targetBp in 0..FULL_BP }
        .associateBy { it.asOf }
        .values
        .mapNotNull { o -> posOf[o.asOf]?.let { Located(o, it) } }
        .sortedBy { it.pos }
}

/** 성숙 관측 중 시장가(둘 다 정의됨)로 이후 63일 수익률까지 낼 수 있는 것만 남긴다. */
private fun pricedMatured(
    matured: List<Located>,
    prices: DoubleArray,
): List<Matured> =
    matured.mapNotNull { loc ->
        val fwd = windowReturn(prices, loc.pos, loc.pos + Track.HORIZON)
        if (fwd.isNaN()) null else Matured(loc, Breadth.bandOf(loc.obs.targetBp), fwd)
    }

private fun gate(matured: List<Located>): Gate {
    if (matured.isEmpty()) return Gate(0, 0, 0)
    val span = matured.last().pos - matured.first().pos
    val bandCount = matured.map { Breadth.bandOf(it.obs.targetBp) }.distinct().size
    return Gate(matured.size, span, bandCount)
}

// ---------------------------------------------------------------- 밴드 통계·판정 A

/** 밴드별 이후 63일 수익률 분포와 그 창 안 일별 수익률의 변동성. 성숙+유가 관측이 1건이라도 있는 밴드만 남는다. */
private fun bandStats(
    pricedMatured: List<Matured>,
    prices: DoubleArray,
): List<BandStats> =
    pricedMatured
        .groupBy { it.band }
        .map { (band, group) ->
            val fwd = group.map { it.fwd }
            val daily = pooledDailyReturns(group, prices)
            BandStats(
                band = band,
                n = group.size,
                vol = sampleStdDev(daily) * sqrt(TRADING_YEAR.toDouble()),
                drop10 = fwd.count { it < -0.10 }.toDouble() / fwd.size,
                rise10 = fwd.count { it > 0.10 }.toDouble() / fwd.size,
                worst = fwd.min(),
                median = median(fwd),
            )
        }.sortedBy { it.band.ordinal }

/** 밴드 한 그룹의 모든 관측이 낸 이후 63일 창의 일별 수익률을 하나로 모은다(창이 겹쳐도 그대로 합친다). */
private fun pooledDailyReturns(
    group: List<Matured>,
    prices: DoubleArray,
): List<Double> {
    val out = mutableListOf<Double>()
    for (m in group) {
        val pos = m.located.pos
        for (i in pos + 1..pos + Track.HORIZON) {
            val r = windowReturn(prices, i - 1, i)
            if (!r.isNaN()) out.add(r)
        }
    }
    return out
}

/** 표본 5 이상인 밴드가 2종 미만이면 UNKNOWN. 아니면 가장 낮은 밴드가 가장 높은 밴드보다 변동성·하락확률이 크면 MATCH. */
private fun riskFit(bandStats: List<BandStats>): Fit {
    val qualifying = bandStats.filter { it.n >= Track.MIN_PER_BAND }
    if (qualifying.size < 2) return Fit.UNKNOWN
    val lowest = qualifying.minBy { it.band.ordinal }
    val highest = qualifying.maxBy { it.band.ordinal }
    return if (lowest.vol > highest.vol && lowest.drop10 >= highest.drop10) Fit.MATCH else Fit.MISMATCH
}

/** NEUTRAL 을 뺀 성숙 관측 중 밴드가 가리킨 방향과 실제 방향이 맞은 비율. 모델이 내세우는 주장은 아니다(판정 C). */
private fun direction(pricedMatured: List<Matured>): Double? {
    val considered = pricedMatured.filter { it.band != Band.NEUTRAL }
    if (considered.isEmpty()) return null
    val hits =
        considered.count { m ->
            (m.band >= Band.ACTIVE && m.fwd > 0) || (m.band <= Band.DEFENSE && m.fwd < 0)
        }
    return hits.toDouble() / considered.size
}

// ---------------------------------------------------------------- 시뮬레이션·판정 B

private fun simulateFromObs(
    locatedAll: List<Located>,
    days: Int,
    prices: DoubleArray,
): Sim {
    val targetArr = IntArray(days) { -1 }
    for (loc in locatedAll) targetArr[loc.pos] = loc.obs.targetBp
    return Track.simulate(targetArr, prices)
}

/** `strategy == null` 이면 UNKNOWN. 모의 MDD 가 단순 보유보다 얕고(수가 더 크고) TRIPPED 가 하나도 없으면 MATCH. */
private fun strategyFit(strategy: Strategy?): Fit {
    if (strategy == null) return Fit.UNKNOWN
    val tripped = strategy.tripwires.any { it.check == Check.TRIPPED }
    return if (strategy.mdd > strategy.holdMdd && !tripped) Fit.MATCH else Fit.MISMATCH
}

/** 관측이 2건 미만으로 달력에 매핑되거나 가격 데이터가 전혀 없으면 전략을 평가할 수 없다(null). */
private fun strategy(
    locatedAll: List<Located>,
    prices: DoubleArray,
    sim: Sim,
): Strategy? {
    if (locatedAll.size < 2 || prices.all { it.isNaN() }) return null
    val p0 = locatedAll.first().pos
    val end = prices.lastIndex
    val holdNav = buyAndHold(prices, p0, end)
    val spanLen = end - p0 + 1
    return Strategy(
        ret = sim.nav[end] / sim.nav[p0] - 1.0,
        mdd = maxDrawdown(sim.nav, p0, end),
        holdRet = holdNav.last() - 1.0,
        holdMdd = maxDrawdown(holdNav, 0, holdNav.lastIndex),
        trades = sim.trades,
        tripwires = tripwires(sim, prices, spanLen, p0, end),
    )
}

private fun buyAndHold(
    prices: DoubleArray,
    from: Int,
    to: Int,
): DoubleArray {
    val out = DoubleArray(to - from + 1)
    out[0] = 1.0
    for (i in 1 until out.size) out[i] = out[i - 1] * (1.0 + dayReturn(prices, from + i))
    return out
}

private fun maxDrawdown(
    nav: DoubleArray,
    from: Int,
    to: Int,
): Double {
    var peak = nav[from]
    var worst = 0.0
    for (t in from..to) {
        if (nav[t] > peak) peak = nav[t]
        val dd = nav[t] / peak - 1.0
        if (dd < worst) worst = dd
    }
    return worst
}

// ---------------------------------------------------------------- 폐기 조건(가이드북 7절)

private fun tripwires(
    sim: Sim,
    prices: DoubleArray,
    spanLen: Int,
    p0: Int,
    end: Int,
): List<Tripwire> {
    val insufficient = spanLen < TRIPWIRE_MIN_DAYS
    return listOf(
        mechanismTripwire(sim.nav, prices, insufficient, spanLen, p0, end),
        drawdownTripwire(sim.nav, insufficient, spanLen, p0, end),
        lossYearTripwire(sim.nav, insufficient, spanLen, p0, end),
        noiseTripwire(sim.held, spanLen, p0, end),
    )
}

private fun insufficientDetail(spanLen: Int): String = "시뮬레이션 일수가 ${spanLen}일로 ${TRIPWIRE_MIN_DAYS}거래일에 못 미쳐 판정할 수 없습니다."

private fun trailingReturn(
    series: DoubleArray,
    t: Int,
): Double = windowReturn(series, t - TRIPWIRE_MIN_DAYS, t)

private fun mechanismTripwire(
    nav: DoubleArray,
    prices: DoubleArray,
    insufficient: Boolean,
    spanLen: Int,
    p0: Int,
    end: Int,
): Tripwire {
    val name = "메커니즘 고장"
    if (insufficient) return Tripwire(name, Check.UNKNOWN, insufficientDetail(spanLen))
    var regimeDays = 0
    var tripped = false
    for (t in p0 + TRIPWIRE_MIN_DAYS..end) {
        val marketTrail = trailingReturn(prices, t)
        if (marketTrail.isNaN() || marketTrail > -0.20) continue
        regimeDays++
        if (trailingReturn(nav, t) < marketTrail) tripped = true
    }
    return when {
        tripped -> Tripwire(name, Check.TRIPPED, "지수 12개월 -20% 이하인 날에 모의 NAV 12개월 수익률이 지수를 밑돌았습니다.")
        regimeDays == 0 -> Tripwire(name, Check.OK, "지수 12개월 -20% 국면 없음")
        else -> Tripwire(name, Check.OK, "지수 12개월 -20% 이하인 날 ${regimeDays}일 동안 모의 NAV 가 지수를 밑돈 적이 없습니다.")
    }
}

private fun drawdownTripwire(
    nav: DoubleArray,
    insufficient: Boolean,
    spanLen: Int,
    p0: Int,
    end: Int,
): Tripwire {
    val name = "낙폭 초과"
    if (insufficient) return Tripwire(name, Check.UNKNOWN, insufficientDetail(spanLen))
    val mdd = maxDrawdown(nav, p0, end)
    return if (mdd < -0.30) {
        Tripwire(name, Check.TRIPPED, "모의 최대낙폭이 ${onePct(mdd)}%로 -30%를 넘었습니다.")
    } else {
        Tripwire(name, Check.OK, "모의 최대낙폭 ${onePct(mdd)}%로 -30% 이내입니다.")
    }
}

private fun lossYearTripwire(
    nav: DoubleArray,
    insufficient: Boolean,
    spanLen: Int,
    p0: Int,
    end: Int,
): Tripwire {
    val name = "12개월 손실 초과"
    if (insufficient) return Tripwire(name, Check.UNKNOWN, insufficientDetail(spanLen))
    var worst = Double.NaN
    for (t in p0 + TRIPWIRE_MIN_DAYS..end) {
        val trail = trailingReturn(nav, t)
        if (!trail.isNaN() && (worst.isNaN() || trail < worst)) worst = trail
    }
    return if (!worst.isNaN() && worst < -0.20) {
        Tripwire(name, Check.TRIPPED, "12개월 수익률 최저치가 ${onePct(worst)}%로 -20%를 밑돌았습니다.")
    } else {
        Tripwire(name, Check.OK, "12개월 수익률이 -20% 밑으로 내려간 적이 없습니다.")
    }
}

private fun noiseTripwire(
    held: DoubleArray,
    spanLen: Int,
    p0: Int,
    end: Int,
): Tripwire {
    val name = "신호 노이즈화"
    val window = minOf(TRIPWIRE_MIN_DAYS, spanLen)
    val from = maxOf(p0 + 1, end - window + 1)
    var recent = 0
    for (t in from..end) if (held[t] != held[t - 1]) recent++
    return when {
        recent > NOISE_MAX_TRADES -> Tripwire(name, Check.TRIPPED, "최근 ${window}거래일 동안 실행이 ${recent}회로 15회를 넘었습니다.")
        spanLen >= TRIPWIRE_MIN_DAYS -> Tripwire(name, Check.OK, "최근 ${window}거래일 동안 실행이 ${recent}회로 15회 이내입니다.")
        else -> Tripwire(name, Check.UNKNOWN, insufficientDetail(spanLen))
    }
}

// ---------------------------------------------------------------- 상관관계 추이

/** 동행 상관의 재료: 관측 시점의 score 와 그 직전 63거래일 지수 수익률. */
private fun coincidentCorr(
    located: List<Located>,
    prices: DoubleArray,
    dates: List<String>,
): DoubleArray {
    val x = DoubleArray(dates.size) { Double.NaN }
    val y = DoubleArray(dates.size) { Double.NaN }
    for (loc in located) {
        val ret = windowReturn(prices, loc.pos - Track.HORIZON, loc.pos)
        if (ret.isNaN()) continue
        x[loc.pos] = loc.obs.score
        y[loc.pos] = ret
    }
    return Track.rollingCorr(x, y, dates)
}

/** 선행 상관의 재료: 성숙 관측의 score 와 그 이후 63거래일 지수 수익률. */
private fun predictiveCorr(
    pricedMatured: List<Matured>,
    dates: List<String>,
): DoubleArray {
    val x = DoubleArray(dates.size) { Double.NaN }
    val y = DoubleArray(dates.size) { Double.NaN }
    for (m in pricedMatured) {
        x[m.located.pos] = m.located.obs.score
        y[m.located.pos] = m.fwd
    }
    return Track.rollingCorr(x, y, dates)
}

private fun pairsAtLag(
    dx: DoubleArray,
    r: DoubleArray,
    k: Int,
): Pair<DoubleArray, DoubleArray> {
    val xs = mutableListOf<Double>()
    val ys = mutableListOf<Double>()
    for (t in dx.indices) {
        val rt = t - k
        if (rt !in r.indices) continue
        val a = dx[t]
        val b = r[rt]
        if (!a.isNaN() && !b.isNaN()) {
            xs.add(a)
            ys.add(b)
        }
    }
    return xs.toDoubleArray() to ys.toDoubleArray()
}

// ---------------------------------------------------------------- 원인 분석(§3)

private fun diagnoses(
    matured: List<Located>,
    locatedAll: List<Located>,
    bandStats: List<BandStats>,
    market: Market,
    prices: DoubleArray,
    retroScore: DoubleArray?,
    applied: List<Applied>,
    sim: Sim,
): List<Diagnosis> =
    listOf(
        diagnoseNarrow(matured, market),
        diagnoseLag(retroScore, prices),
        diagnosePartial(matured),
        diagnoseBoundary(locatedAll),
        diagnoseSuppressed(locatedAll, sim.trades),
        diagnoseNotApplied(sim, market.dates, applied),
        diagnoseUniverse(matured),
        diagnoseStale(locatedAll),
        diagnoseSample(bandStats),
    )

private fun changedPairs(located: List<Located>): List<Pair<Located, Located>> =
    (1 until located.size)
        .filter { located[it].obs.targetBp != located[it - 1].obs.targetBp }
        .map { located[it - 1] to located[it] }

private fun isLowBand(obs: Obs): Boolean {
    val band = Breadth.bandOf(obs.targetBp)
    return band == Band.DEFENSE || band == Band.MAX_DEFENSE
}

/** 낮은 밴드 관측 중 지수가 오른 것들의 (지수 수익률 − 동일가중 수익률) 목록. */
private fun lowBandRisingGaps(
    matured: List<Located>,
    index: DoubleArray,
    equal: DoubleArray,
): List<Double> =
    matured
        .filter { isLowBand(it.obs) }
        .mapNotNull { loc ->
            val indexRet = windowReturn(index, loc.pos, loc.pos + Track.HORIZON)
            val equalRet = windowReturn(equal, loc.pos, loc.pos + Track.HORIZON)
            if (indexRet.isNaN() || equalRet.isNaN() || indexRet <= 0.0) null else indexRet - equalRet
        }

/** 069500 지수와 동일가중 평균을 직접 비교한다 — 시장폭은 종목 수를, 지수는 시가총액을 가중하므로 좁은 장세에서 둘이 갈린다. */
private fun diagnoseNarrow(
    matured: List<Located>,
    market: Market,
): Diagnosis {
    val index = market.index ?: return Diagnosis(Cause.NARROW, false, "069500 지수 시계열이 없어 좁은 장세 여부를 판정할 수 없습니다.")
    val gaps = lowBandRisingGaps(matured, index, market.equal)
    if (gaps.isEmpty()) return Diagnosis(Cause.NARROW, false, "낮은 밴드에서 지수가 오른 관측이 없어 좁은 장세 여부를 판정할 수 없습니다.")
    val avg = gaps.average()
    val text = "낮은 밴드에서 지수가 오른 관측 ${gaps.size}건에서 지수가 동일가중 평균을 평균 ${onePct(avg)}%p 앞섰습니다."
    return Diagnosis(Cause.NARROW, avg > NARROW_THRESHOLD, text)
}

private fun diagnoseLag(
    retroScore: DoubleArray?,
    prices: DoubleArray,
): Diagnosis {
    if (retroScore == null) return Diagnosis(Cause.LAG, false, "판정 불가 — 소급 시계열이 없습니다.")
    val k =
        Track.lagPeak(firstDiff(retroScore), dailyReturn(prices))
            ?: return Diagnosis(Cause.LAG, false, "판정 불가 — 교차상관을 계산할 표본이 부족합니다.")
    return Diagnosis(Cause.LAG, k >= LAG_THRESHOLD, "신호 변화와 지수 수익률의 교차상관이 최대가 되는 시차는 ${k}일입니다.")
}

private fun diagnosePartial(matured: List<Located>): Diagnosis {
    if (matured.isEmpty()) return Diagnosis(Cause.PARTIAL, false, "성숙 관측이 없어 부분 창 여부를 판정할 수 없습니다.")
    val count = matured.count { it.obs.window < Breadth.PCT_WIN }
    val share = count.toDouble() / matured.size
    val text = "성숙 관측 ${matured.size}건 중 ${count}건(${wholePct(share)}%)이 완전 창(${Breadth.PCT_WIN}일) 미만입니다."
    return Diagnosis(Cause.PARTIAL, share > PARTIAL_THRESHOLD, text)
}

private fun distToBoundary(score: Double): Double {
    var best = Double.MAX_VALUE
    for (k in 0..7) {
        val boundary = (2 * k + 1) / 16.0
        val dist = abs(score - boundary)
        if (dist < best) best = dist
    }
    return best
}

private fun diagnoseBoundary(located: List<Located>): Diagnosis {
    val pairs = changedPairs(located)
    if (pairs.isEmpty()) return Diagnosis(Cause.BOUNDARY, false, "목표가 바뀐 관측 쌍이 없어 경계 잡음 여부를 판정할 수 없습니다.")
    val near =
        pairs.count { (a, b) ->
            distToBoundary(a.obs.score) <= BOUNDARY_TOLERANCE || distToBoundary(b.obs.score) <= BOUNDARY_TOLERANCE
        }
    val share = near.toDouble() / pairs.size
    val text = "목표가 바뀐 관측 쌍 ${pairs.size}건 중 ${near}건(${wholePct(share)}%)이 양자화 경계 1/32 안에 있습니다."
    return Diagnosis(Cause.BOUNDARY, share > BOUNDARY_THRESHOLD, text)
}

private fun diagnoseSuppressed(
    located: List<Located>,
    trades: Int,
): Diagnosis {
    val changes = changedPairs(located).size
    if (changes == 0) return Diagnosis(Cause.SUPPRESSED, false, "목표 변경이 없어 억제 여부를 판정할 수 없습니다.")
    val text = "목표 변경 ${changes}회 중 모의 실행은 ${trades}회입니다."
    return Diagnosis(Cause.SUPPRESSED, trades < changes / 2.0, text)
}

private fun executionDays(held: DoubleArray): List<Int> =
    (1 until held.size).filter {
        !held[it].isNaN() && !held[it - 1].isNaN() && held[it] != held[it - 1]
    }

private fun diagnoseNotApplied(
    sim: Sim,
    dates: List<String>,
    applied: List<Applied>,
): Diagnosis {
    val execDays = executionDays(sim.held)
    if (execDays.isEmpty()) return Diagnosis(Cause.NOT_APPLIED, false, "모의 실행이 없어 적용 여부를 판정할 수 없습니다.")
    if (applied.isEmpty()) return Diagnosis(Cause.NOT_APPLIED, true, "적용 기록이 없습니다.")
    val posOf = dates.withIndex().associate { (i, d) -> d to i }
    val appliedPos = applied.mapNotNull { posOf[it.date] }
    val missed = execDays.count { t -> appliedPos.none { p -> p in t..(t + APPLY_WINDOW) } }
    val text = "모의 실행 ${execDays.size}건 중 ${missed}건이 5거래일 안에 적용되지 않았습니다."
    return Diagnosis(Cause.NOT_APPLIED, missed >= 1, text)
}

private fun diagnoseUniverse(matured: List<Located>): Diagnosis {
    val distinct = matured.map { it.obs.universeAt }.distinct().size
    return Diagnosis(Cause.UNIVERSE, distinct >= 2, "성숙 관측의 유니버스 기준일이 ${distinct}종입니다.")
}

private fun daysBetween(
    asOf: String,
    computedOn: String,
): Long {
    val a = LocalDate.parse(asOf, DATE_FMT)
    val b = LocalDate.parse(computedOn, DATE_FMT)
    return ChronoUnit.DAYS.between(a, b)
}

private fun diagnoseStale(located: List<Located>): Diagnosis {
    val stale = located.count { daysBetween(it.obs.asOf, it.obs.computedOn) > STALE_DAYS }
    val share = stale.toDouble() / located.size
    val text = "관측 ${located.size}건 중 ${stale}건(${wholePct(share)}%)이 계산일과 관측일의 차이가 7일을 넘습니다."
    return Diagnosis(Cause.STALE, share > STALE_THRESHOLD, text)
}

private fun diagnoseSample(bandStats: List<BandStats>): Diagnosis {
    val thin = bandStats.filter { it.n < Track.MIN_PER_BAND }
    if (thin.isEmpty()) return Diagnosis(Cause.SAMPLE, false, "모든 밴드의 표본이 ${Track.MIN_PER_BAND}건 이상입니다.")
    val names = thin.joinToString(", ") { it.band.label() }
    return Diagnosis(Cause.SAMPLE, true, "$names 밴드의 표본이 ${Track.MIN_PER_BAND}건 미만입니다.")
}
