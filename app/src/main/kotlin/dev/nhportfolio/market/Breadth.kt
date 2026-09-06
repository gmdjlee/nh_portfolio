package dev.nhportfolio.market

import kotlin.math.abs
import kotlin.math.round

/** 일봉 한 줄. NH 필드명을 모르는 시장 무관 타입이다.
 *  [refPrice] 는 기준가, [exRight] 는 액면분할·병합·권리락이 있었던 날인지. */
data class Bar(
    val date: String,
    val close: Int,
    val refPrice: Int,
    val exRight: Boolean,
)

enum class Band { MAX_DEFENSE, DEFENSE, NEUTRAL, ACTIVE, MAX_INVEST }

enum class Action { HOLD, CUT, ADD }

data class Signal(
    val targetBp: Int,
    val band: Band,
    val breadth: Double,
    val pctile: Double,
    val window: Int,
    val asOf: String,
    /** 아홉 구성을 1/8 단위로 양자화한 뒤 평균한 값(0~1). 최종 목표([targetBp])는 이 값을 한 번 더 양자화한 것이다. */
    val score: Double,
)

/** 판정 결과. [gapBp] 는 목표 − 유지 비중(bp)이고, [action] 은 그 절댓값이 임계치를 넘었을 때의 방향이다. */
data class Verdict(
    val action: Action,
    val gapBp: Int,
)

/**
 * 시장폭(breadth) 기반 주식 익스포저 목표. 순수 함수 — 네트워크도 안드로이드도 모른다.
 *
 * 종가 -> 200일선 상회 비율 -> 3년 백분위 -> 평활(sm) -> 앙상블(이동평균 길이 3개 × 평활 길이
 * 3개 = 9개 구성) -> 1250bp 단위로 양자화. 이 파일에서 틀리면 사용자가 잘못된 금액으로
 * 매매하게 되므로, 반올림은 전부 은행가 반올림([kotlin.math.round])만 쓴다.
 */
object Breadth {
    const val PCT_WIN = 756
    const val PCT_MIN = 252

    private val MA_LENGTHS = listOf(150, 200, 250)
    private val SM_LENGTHS = listOf(20, 40, 60)
    private const val MA_DISPLAY = 200
    private const val MIN_VALID_STOCKS = 30
    private const val EIGHTHS = 8
    private const val FULL_BP = 10_000
    private const val HOLD_THRESHOLD_BP = 1_500

    /**
     * 수정주가 보정. 최신에서 과거 방향으로 훑으며, 액면분할·병합·권리락이 있었던 날의
     * 기준가와 전일 **원본** 종가의 비율이 2% 를 넘게 벌어질 때만 그 이전 종가에 누적
     * 곱한다. 분모를 원본 종가로 고정해야 한다 — 이미 보정된(뒤쪽 권리락의 배수가 곱해진)
     * 값을 분모로 쓰면 분할이 두 번 이상 겹칠 때 계수가 어긋나 보정이 조용히 건너뛰어진다.
     * 응답이 이미 수정주가면 비율이 1 에 붙어 저절로 통과한다.
     */
    fun adjust(bars: List<Bar>): IntArray {
        val adjusted = DoubleArray(bars.size) { bars[it].close.toDouble() }
        for (i in bars.lastIndex downTo 1) {
            val bar = bars[i]
            if (!bar.exRight) continue
            val factor = bar.refPrice.toDouble() / bars[i - 1].close
            if (abs(factor - 1.0) > 0.02) {
                for (j in 0 until i) adjusted[j] *= factor
            }
        }
        return IntArray(adjusted.size) { round(adjusted[it]).toInt() }
    }

    /**
     * [dates] 와 길이가 같은 날짜별 신호. 원소 t 는 dates[t] 시점의 신호다. [closes] 의 각
     * 배열은 [dates] 와 길이가 같고, 상장 전이라 값이 없는 날은 0 이다(주가는 0 이 될 수
     * 없으므로 안전한 빈칸이다).
     *
     * 200일선 상회 비율이나 그 3년 백분위, 혹은 아홉 평활 구성 중 하나라도 그 날 정의되지
     * 않으면(관측 부족, 유효 종목 30 미만, 창 워밍업) 그 원소는 null 이다 — 근거 없는 숫자를
     * 내느니 아무 숫자도 안 내는 쪽이 낫다. 세 이동평균 계열과 그 백분위·롤링 평활·롤링
     * 정의 개수를 각각 전 구간에 걸쳐 한 번씩만 계산한 뒤 날짜별로 조립하므로, 전체가
     * O(n) 한 번의 훑기로 끝난다 — 날짜를 잘라 [signal] 을 다시 부른 것과 결과가 같다.
     */
    fun series(
        closes: Map<String, IntArray>,
        dates: List<String>,
    ): List<Signal?> {
        if (dates.isEmpty()) return emptyList()
        val days = dates.size
        val stocks = closes.values

        val breadthByMa = MA_LENGTHS.associateWith { ma -> breadthSeries(stocks, days, ma) }
        val pctileByMa = breadthByMa.mapValues { (_, breadth) -> pctRank(breadth) }
        val windowByMa = MA_LENGTHS.associateWith { ma -> rollingDefinedCount(breadthByMa.getValue(ma), PCT_WIN) }
        val smoothsByConfig = MA_LENGTHS.flatMap { ma -> SM_LENGTHS.map { sm -> rollingSmooth(pctileByMa.getValue(ma), sm) } }
        val displayBreadth = breadthByMa.getValue(MA_DISPLAY)
        val displayPctile = pctileByMa.getValue(MA_DISPLAY)

        return List(days) { t -> signalAt(t, dates, displayBreadth, displayPctile, windowByMa, smoothsByConfig) }
    }

    /** 오늘의 익스포저 목표. [series] 의 마지막 원소와 정확히 같다(빈 [dates] 는 null). */
    fun signal(
        closes: Map<String, IntArray>,
        dates: List<String>,
    ): Signal? = series(closes, dates).lastOrNull()

    /** [series] 의 날짜별 조립. 날짜 [t] 하나에서 아홉 구성이 전부 정의될 때만 [Signal] 을 낸다. */
    private fun signalAt(
        t: Int,
        dates: List<String>,
        displayBreadth: DoubleArray,
        displayPctile: DoubleArray,
        windowByMa: Map<Int, IntArray>,
        smoothsByConfig: List<DoubleArray>,
    ): Signal? {
        val breadth = displayBreadth[t]
        if (breadth.isNaN()) return null
        val pctile = displayPctile[t]
        if (pctile.isNaN()) return null

        // 목표는 9구성 전부의 평균이다(사양 §2 의 5) — 구성이 하나라도 아직 평활되지
        // 않았으면(창 워밍업) 부분 앙상블을 대신 내지 않고 null 이다. 원 코드는 워밍업
        // 구간을 중립값으로 채워 늘 9개를 채우는데, 이 채움을 일부러 옮기지 않았으므로
        // 대신 9개가 다 찰 때까지 기다린다.
        val smooths = smoothsByConfig.map { it[t] }
        if (smooths.any { it.isNaN() }) return null

        val ensembleScore = score(smooths)
        val targetBp = quantize(ensembleScore)
        return Signal(
            targetBp = targetBp,
            band = bandOf(targetBp),
            breadth = breadth,
            pctile = pctile,
            // 세 이동평균 길이 중 창이 가장 늦게 차는 쪽이 병목이다 — 200일선만 보면
            // 250일선이 아직 부분 창인데도 756/756 으로 보여 다 찬 것처럼 속일 수 있다.
            window = MA_LENGTHS.minOf { windowByMa.getValue(it)[t] },
            asOf = dates[t],
            score = ensembleScore,
        )
    }

    fun bandOf(targetBp: Int): Band =
        when {
            targetBp < 1_300 -> Band.MAX_DEFENSE
            targetBp < 4_400 -> Band.DEFENSE
            targetBp < 5_600 -> Band.NEUTRAL
            targetBp < 8_150 -> Band.ACTIVE
            else -> Band.MAX_INVEST
        }

    fun verdict(
        targetBp: Int,
        heldBp: Int,
    ): Verdict {
        val gapBp = targetBp - heldBp
        val action =
            when {
                abs(gapBp) < HOLD_THRESHOLD_BP -> Action.HOLD
                gapBp < 0 -> Action.CUT
                else -> Action.ADD
            }
        return Verdict(action, gapBp)
    }

    /**
     * 종목군의 [ma]일선 상회 비율 시계열. 분모는 종가와 이동평균이 둘 다 정의된 종목 수이며,
     * 30 미만이면 그 날은 미정의(NaN)다. 이동평균은 이동합으로 계산해 창마다 O(1)로 갱신한다.
     */
    private fun breadthSeries(
        stocks: Collection<IntArray>,
        days: Int,
        ma: Int,
    ): DoubleArray {
        val minPeriods = (ma * 0.75).toInt()
        val aboveCount = IntArray(days)
        val validCount = IntArray(days)
        for (closes in stocks) accumulate(closes, days, ma, minPeriods, aboveCount, validCount)
        return DoubleArray(days) { t ->
            if (validCount[t] >= MIN_VALID_STOCKS) aboveCount[t].toDouble() / validCount[t] else Double.NaN
        }
    }

    /** 종목 하나를 훑으며 이동합/이동개수로 [ma]일선을 유지하고, 유효·상회 여부를 누적한다. */
    private fun accumulate(
        closes: IntArray,
        days: Int,
        ma: Int,
        minPeriods: Int,
        aboveCount: IntArray,
        validCount: IntArray,
    ) {
        var sum = 0L
        var count = 0
        for (t in 0 until days) {
            val close = closes[t]
            if (close != 0) {
                sum += close
                count++
            }
            if (t >= ma) {
                val dropped = closes[t - ma]
                if (dropped != 0) {
                    sum -= dropped
                    count--
                }
            }
            if (count < minPeriods || close == 0) continue
            validCount[t]++
            if (close > sum.toDouble() / count) aboveCount[t]++
        }
    }

    /**
     * [values] 의 트레일링 백분위. 창 [win] 안의 정의된(비-NaN) 관측이 [minPeriods] 개
     * 미만이면 NaN 이다. 동점은 평균 순위 — 오늘 값도 n 개 중 하나로 포함되어 "같음"에
     * 스스로 잡힌다.
     */
    internal fun pctRank(
        values: DoubleArray,
        win: Int = PCT_WIN,
        minPeriods: Int = PCT_MIN,
    ): DoubleArray =
        DoubleArray(values.size) { i ->
            val current = values[i]
            if (current.isNaN()) {
                Double.NaN
            } else {
                val from = maxOf(0, i - win + 1)
                var n = 0
                var less = 0
                var equal = 0
                for (j in from..i) {
                    val v = values[j]
                    if (v.isNaN()) continue
                    n++
                    when {
                        v < current -> less++
                        v == current -> equal++
                    }
                }
                if (n < minPeriods) Double.NaN else (less + (equal + 1) / 2.0) / n
            }
        }

    /**
     * 트레일링 [win] 창 안에 정의된(비-NaN) 관측 수의 날짜별 시계열 — [Signal.window] 의
     * 재료다. 이동개수를 유지해 날짜당 O(1)로 갱신한다(위 [accumulate] 와 같은 요령).
     */
    private fun rollingDefinedCount(
        values: DoubleArray,
        win: Int,
    ): IntArray {
        val out = IntArray(values.size)
        var count = 0
        for (t in values.indices) {
            if (!values[t].isNaN()) count++
            if (t >= win && !values[t - win].isNaN()) count--
            out[t] = count
        }
        return out
    }

    /**
     * [values] 의 트레일링 [sm]-일 평균 시계열. 창 안에 NaN 이 하나라도 있으면 그 날은
     * NaN 이다. 이동합과 창 안의 NaN 개수를 같이 유지해 날짜당 O(1)로 갱신한다.
     */
    private fun rollingSmooth(
        values: DoubleArray,
        sm: Int,
    ): DoubleArray {
        val out = DoubleArray(values.size)
        var sum = 0.0
        var nanCount = 0
        for (t in values.indices) {
            val v = values[t]
            if (v.isNaN()) nanCount++ else sum += v
            if (t >= sm) {
                val dropped = values[t - sm]
                if (dropped.isNaN()) nanCount-- else sum -= dropped
            }
            out[t] = if (t >= sm - 1 && nanCount == 0) sum / sm else Double.NaN
        }
        return out
    }

    private fun roundToEighth(x: Double): Double = round(x * EIGHTHS) / EIGHTHS

    /** 0~1 스코어 하나를 1250bp 단위로 양자화한다(은행가 반올림). */
    internal fun quantize(x: Double): Int = round(roundToEighth(x) * FULL_BP).toInt()

    /**
     * 아홉 구성을 1/8 단위로 먼저 반올림한 뒤 평균한 값(0~1) — [Signal.score] 그대로다.
     * 평활값을 반올림 없이 바로 평균하면(양자화가 한 번으로 끝나면) 아래 [quantize] 의
     * 결과가 달라진다.
     */
    internal fun score(smooths: List<Double>): Double = smooths.map(::roundToEighth).average()

    /**
     * 앙상블 양자화 — **두 번** 일어난다. [score] 로 구성별 반올림 평균을 낸 뒤, 그 값을
     * 다시 1250bp 단위로 반올림한다. 평활값을 바로 평균해 한 번만 반올림하면 결과가
     * 달라진다(예: 5개 0.19 + 4개 0.17 은 두 번 양자화하면 2500bp, 한 번만 하면 1250bp).
     */
    internal fun quantize(smooths: List<Double>): Int = quantize(score(smooths))
}
