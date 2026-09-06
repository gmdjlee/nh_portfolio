package dev.nhportfolio.market

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.nhportfolio.store.appliedKey
import dev.nhportfolio.store.readApplied
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.round

/**
 * 효용성 화면의 상태. [loading] 이 끝나면 [observed]·[retro] 가 항상 함께 채워진다 — 토글은
 * 이 둘 중 하나를 고르기만 할 뿐 다시 계산하지 않는다(계산은 [TrackViewModel] 의 `init` 에서
 * 한 번뿐이다). [market.dates] 가 비어 있으면(캐시 없음) 화면은 안내 문구만 보여준다.
 */
data class TrackUi(
    val loading: Boolean = true,
    val market: Market = Market(dates = emptyList(), index = null, equal = DoubleArray(0)),
    val universeAt: String = "",
    val observed: Report? = null,
    val retro: Report? = null,
    val observedTarget: DoubleArray = DoubleArray(0),
    val observedDots: List<Pair<Int, Double>> = emptyList(),
    val retroTarget: DoubleArray = DoubleArray(0),
)

/**
 * 효용성 화면의 뷰모델. [market] 의 [MarketData.trackData] 로 시장·관측·소급 신호를 한 번 읽고,
 * 계좌의 적용 기록([readApplied])과 합쳐 관측 기록 판정과 소급 판정을 둘 다 미리 계산해 둔다.
 * 토글이 매번 다시 계산하면 소급 시계열(전 구간 일별 신호)을 화면 전환마다 다시 도는 비용이 든다.
 */
class TrackViewModel(
    acctNo: String,
    private val market: MarketData,
    private val store: DataStore<Preferences>,
) : ViewModel() {
    private val appliedKey = appliedKey(acctNo)
    private val _ui = MutableStateFlow(TrackUi())
    val ui: StateFlow<TrackUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val (data, applied) =
                withContext(Dispatchers.IO) {
                    market.trackData() to readApplied(store.data.first(), appliedKey)
                }
            _ui.value = withContext(Dispatchers.Default) { buildUi(data, applied) }
        }
    }
}

private const val BP_SCALE = 10_000.0

/**
 * [data] 하나로 관측 기록 판정과 소급 판정을 둘 다 만든다. [retroScores] 는 두 판정 모두에
 * 넘긴다 — 시차(LAG) 계산이 일별 점수 계열을 요구하는데 관측 기록은 성기기 때문이다(사양 §3).
 */
private fun buildUi(
    data: TrackData,
    applied: List<Applied>,
): TrackUi {
    val scores = retroScores(data.retro)
    val observed = Track.report(data.obs, data.market, applied, scores)
    val retro = Track.report(retroObs(data.market.dates, data.retro, data.universeAt), data.market, applied, scores)
    return TrackUi(
        loading = false,
        market = data.market,
        universeAt = data.universeAt,
        observed = observed,
        retro = retro,
        observedTarget = observedTargetSeries(data.market.dates, data.obs),
        observedDots = observedDots(data.market.dates, data.obs),
        retroTarget = retroTargetSeries(data.retro),
    )
}

/** [obs] 행이 있는 날부터 다음 행 전까지 목표를 유지하는 계단값. 첫 행 이전은 NaN(모르는 구간). */
private fun observedTargetSeries(
    dates: List<String>,
    obs: List<Obs>,
): DoubleArray {
    val byDate = obs.associateBy { it.asOf }
    val out = DoubleArray(dates.size)
    var last = Double.NaN
    for (i in dates.indices) {
        byDate[dates[i]]?.let { last = it.targetBp / BP_SCALE }
        out[i] = last
    }
    return out
}

/** 관측 행을 점으로 찍을 (달력 위치, 목표 비율) 목록. 달력에 없는 날짜의 행은 그릴 자리가 없어 건너뛴다. */
private fun observedDots(
    dates: List<String>,
    obs: List<Obs>,
): List<Pair<Int, Double>> {
    val positionOf = dates.withIndex().associate { (i, d) -> d to i }
    return obs.mapNotNull { o -> positionOf[o.asOf]?.let { pos -> pos to o.targetBp / BP_SCALE } }
}

/** 소급 모드의 일별 목표. 신호가 없는 날(워밍업 등)은 NaN. */
private fun retroTargetSeries(retro: List<Signal?>): DoubleArray =
    DoubleArray(retro.size) { i ->
        retro[i]?.targetBp?.div(BP_SCALE)
            ?: Double.NaN
    }

/**
 * [Signal] 을 소급 모드의 [Obs] 로 바꾼다. `computedOn` 을 `asOf` 와 같게 두는 것은 소급 계산이
 * "그날 다시 계산했다면"이라는 가정이라 지연(계산 지연)이 있을 수 없기 때문이다 — 그래서
 * 소급 모드에서는 STALE 진단이 항상 해당 없음이다([diagnosisText] 참고).
 */
internal fun retroObs(
    dates: List<String>,
    retro: List<Signal?>,
    universeAt: String,
): List<Obs> =
    dates.indices.mapNotNull { i ->
        val signal = retro[i] ?: return@mapNotNull null
        Obs(
            asOf = dates[i],
            computedOn = dates[i],
            targetBp = signal.targetBp,
            score = signal.score,
            window = signal.window,
            universeAt = universeAt,
        )
    }

/** 날짜별 점수(신호가 없으면 NaN). 시차(LAG) 계산이 일별 계열을 요구해 두 판정 모두에 넘긴다. */
internal fun retroScores(retro: List<Signal?>): DoubleArray = DoubleArray(retro.size) { i -> retro[i]?.score ?: Double.NaN }

/**
 * 효용성 추적 화면. [acctNo] 는 적용 기록이 계좌별이라 필요할 뿐 화면 어디에도 표시하지 않는다.
 * 이 화면은 [Track] 의 데이터 클래스만 알고 파일 형식·NH 필드명은 모른다.
 */
@Composable
fun TrackScreen(
    acctNo: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: TrackViewModel = koinViewModel { parametersOf(acctNo) },
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("효용성 추적") },
                navigationIcon = { TextButton(onClick = onBack) { Text("뒤로") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val observed = ui.observed
            val retro = ui.retro
            when {
                ui.loading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                ui.market.dates.isEmpty() -> {
                    Text(
                        "시장 데이터 캐시가 없습니다. 포트폴리오 화면에서 갱신을 먼저 하세요.",
                        modifier = Modifier.align(Alignment.Center).padding(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                observed != null && retro != null -> {
                    TrackContent(ui, observed, retro)
                }
            }
        }
    }
}

@Composable
private fun TrackContent(
    ui: TrackUi,
    observed: Report,
    retro: Report,
) {
    var retroMode by remember { mutableStateOf(false) }
    val report = if (retroMode) retro else observed
    val targetSeries = if (retroMode) ui.retroTarget else ui.observedTarget
    val dots = if (retroMode) emptyList() else ui.observedDots

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item { ModeToggle(retroMode, onChange = { retroMode = it }) }
        item { MarketChart(ui.market, targetSeries, dots) }
        item { CorrelationChart(ui.market.dates, report) }
        item { VerdictCard(report) }
        item { BandTable(report) }
        item { TripwireSection(report) }
        item { DiagnosisSection(report, retroMode) }
        item { FootnoteSection(retroMode, ui.universeAt) }
    }
}

private val TRACK_MODES = listOf("관측 기록", "소급 계산")

/** 상단 토글. 아래 모든 내용이 이 값에 따라 바뀐다(사양 §6) — 토글 자체는 다시 계산하지 않는다. */
@Composable
private fun ModeToggle(
    retro: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        TRACK_MODES.forEachIndexed { index, label ->
            SegmentedButton(
                selected = (index == 1) == retro,
                onClick = { onChange(index == 1) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = TRACK_MODES.size),
            ) { Text(label) }
        }
    }
}

// ---- 차트 ----

private val CHART1_HEIGHT = 220.dp
private val CHART2_HEIGHT = 160.dp
private const val LEVEL_BASE = 100.0
private const val RANGE_PAD_FRACTION = 0.05
private const val SHADE_ALPHA = 0.3f
private val LINE_WIDTH = 2.dp
private val DOT_RADIUS = 3.dp
private val LABEL_GAP = 8.dp

/**
 * 차트 1: 069500·동일가중 평균(왼쪽 축, 첫날 100)과 모델 목표(오른쪽 축, 0~100%). 마지막
 * [Track.HORIZON] 거래일은 "아직 성숙하지 않음" 음영을 깐다(사양 §6).
 */
@Composable
private fun MarketChart(
    market: Market,
    targetSeries: DoubleArray,
    dots: List<Pair<Int, Double>>,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodySmall
    val indexColor = MaterialTheme.colorScheme.primary
    val equalColor = MaterialTheme.colorScheme.secondary
    val targetColor = MaterialTheme.colorScheme.tertiary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val shadeColor = MaterialTheme.colorScheme.surfaceVariant

    val dates = market.dates
    val indexLevel = market.index?.let { arr -> DoubleArray(arr.size) { i -> arr[i] * LEVEL_BASE } }
    val equalLevel = DoubleArray(market.equal.size) { i -> market.equal[i] * LEVEL_BASE }
    val (lo, hi) = levelRange(indexLevel, equalLevel)

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("시장과 신호", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        LineChartCanvas(CHART1_HEIGHT) {
            val toX = xMapper(dates.size, size.width)
            val toYLevel = yMapper(lo, hi, size.height)
            val toYPct = yMapper(0.0, 1.0, size.height)
            drawHorizonShading(dates.size, toX, shadeColor)
            indexLevel?.let { drawSeries(it, indexColor, step = false, toX, toYLevel) }
            drawSeries(equalLevel, equalColor, step = false, toX, toYLevel)
            drawSeries(targetSeries, targetColor, step = true, toX, toYPct)
            drawDots(dots, targetColor, toX, toYPct)
            drawChart1Labels(measurer, labelStyle, labelColor, dates, lo, hi)
        }
        ChartLegend(
            "069500" to (indexLevel?.let { levelText(lastDefined(it)) } ?: "-"),
            "동일가중 평균" to levelText(lastDefined(equalLevel)),
            "모델 목표" to pct0(lastDefined(targetSeries)),
        )
    }
}

/** 차트 2: 동행·선행 롤링 상관, −1~+1 축, 0 기준선(사양 §6). */
@Composable
private fun CorrelationChart(
    dates: List<String>,
    report: Report,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.bodySmall
    val coincidentColor = MaterialTheme.colorScheme.primary
    val predictiveColor = MaterialTheme.colorScheme.secondary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("상관관계 추이", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        LineChartCanvas(CHART2_HEIGHT) {
            val toX = xMapper(dates.size, size.width)
            val toY = yMapper(-1.0, 1.0, size.height)
            drawHorizontalLine(toY(0.0), gridColor)
            drawSeries(report.coincident, coincidentColor, step = false, toX, toY)
            drawSeries(report.predictive, predictiveColor, step = false, toX, toY)
            drawChart2Labels(measurer, labelStyle, labelColor, dates)
        }
        ChartLegend(
            "동행" to corrText(lastDefined(report.coincident)),
            "선행" to corrText(lastDefined(report.predictive)),
        )
    }
}

/** 두 차트가 공유하는 유일한 그리기 틀 — 높이는 dp 고정, 너비는 채운다(사양 §6). */
@Composable
private fun LineChartCanvas(
    height: Dp,
    onDraw: DrawScope.() -> Unit,
) {
    Canvas(Modifier.fillMaxWidth().height(height)) { onDraw() }
}

private fun xMapper(
    count: Int,
    width: Float,
): (Int) -> Float {
    val denom = (count - 1).coerceAtLeast(1).toFloat()
    return { i -> width * i / denom }
}

private fun yMapper(
    lo: Double,
    hi: Double,
    height: Float,
): (Double) -> Float {
    val span = (hi - lo).takeIf { it != 0.0 } ?: 1.0
    return { v -> height - ((v - lo) / span).toFloat() * height }
}

/** 왼쪽 축 범위. 069500·동일가중 평균의 정의된 값만으로 min/max 를 잡고 양옆에 작은 여백을 둔다. */
private fun levelRange(
    indexLevel: DoubleArray?,
    equalLevel: DoubleArray,
): Pair<Double, Double> {
    val values = (indexLevel?.toList().orEmpty() + equalLevel.toList()).filterNot { it.isNaN() }
    val lo = values.minOrNull() ?: 0.0
    val hi = values.maxOrNull() ?: LEVEL_BASE
    val pad = ((hi - lo).takeIf { it > 0.0 } ?: LEVEL_BASE) * RANGE_PAD_FRACTION
    return (lo - pad) to (hi + pad)
}

/**
 * 선 하나. NaN 은 붓을 뗀다 — 이어 그리지 않는다. [step] 이면 새 값이 나올 때까지 이전 값을
 * 수평으로 유지하다 수직으로 잇는다("마지막 값을 유지하는 계단", 사양 §6).
 */
private fun DrawScope.drawSeries(
    values: DoubleArray,
    color: Color,
    step: Boolean,
    toX: (Int) -> Float,
    toY: (Double) -> Float,
) {
    val path = Path()
    var active = false
    var lastY = 0f
    for (i in values.indices) {
        val v = values[i]
        if (v.isNaN()) {
            active = false
            continue
        }
        val x = toX(i)
        val y = toY(v)
        when {
            !active -> {
                path.moveTo(x, y)
            }

            step -> {
                path.lineTo(x, lastY)
                path.lineTo(x, y)
            }

            else -> {
                path.lineTo(x, y)
            }
        }
        lastY = y
        active = true
    }
    drawPath(path, color = color, style = Stroke(width = LINE_WIDTH.toPx()))
}

private fun DrawScope.drawDots(
    dots: List<Pair<Int, Double>>,
    color: Color,
    toX: (Int) -> Float,
    toY: (Double) -> Float,
) {
    dots.forEach { (i, v) -> drawCircle(color = color, radius = DOT_RADIUS.toPx(), center = Offset(toX(i), toY(v))) }
}

/** 마지막 [Track.HORIZON] 거래일 음영. 달력이 그보다 짧으면 있는 만큼만 칠한다. */
private fun DrawScope.drawHorizonShading(
    dateCount: Int,
    toX: (Int) -> Float,
    color: Color,
) {
    if (dateCount == 0) return
    val start = (dateCount - Track.HORIZON).coerceAtLeast(0)
    val x0 = toX(start)
    drawRect(color = color.copy(alpha = SHADE_ALPHA), topLeft = Offset(x0, 0f), size = Size(size.width - x0, size.height))
}

private fun DrawScope.drawHorizontalLine(
    y: Float,
    color: Color,
) {
    drawLine(color = color, start = Offset(0f, y), end = Offset(size.width, y), strokeWidth = 1.dp.toPx())
}

private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    style: TextStyle,
    color: Color,
    text: String,
    x: Float,
    y: Float,
) {
    drawText(measurer.measure(text, style = style), color = color, topLeft = Offset(x, y))
}

private fun DrawScope.drawLabelRight(
    measurer: TextMeasurer,
    style: TextStyle,
    color: Color,
    text: String,
    rightX: Float,
    y: Float,
) {
    val layout = measurer.measure(text, style = style)
    drawText(layout, color = color, topLeft = Offset(rightX - layout.size.width, y))
}

/** 왼쪽 위: 최댓값. 아래 줄: 최솟값 + (간격) + 첫 날짜. 오른쪽 아래: 마지막 날짜. */
private fun DrawScope.drawChart1Labels(
    measurer: TextMeasurer,
    style: TextStyle,
    color: Color,
    dates: List<String>,
    lo: Double,
    hi: Double,
) {
    if (dates.isEmpty()) return
    val bottomY = size.height - measurer.measure("0", style = style).size.height
    drawLabel(measurer, style, color, levelText(hi), 0f, 0f)
    val minText = levelText(lo)
    drawLabel(measurer, style, color, minText, 0f, bottomY)
    val minWidth = measurer.measure(minText, style = style).size.width
    drawLabel(measurer, style, color, formatDate(dates.first()), minWidth + LABEL_GAP.toPx(), bottomY)
    drawLabelRight(measurer, style, color, formatDate(dates.last()), size.width, bottomY)
}

/** 위에서 아래로 +1 / 0 / −1, 그 아래 줄에 첫·마지막 날짜(§6: "min/max 와 0"). */
private fun DrawScope.drawChart2Labels(
    measurer: TextMeasurer,
    style: TextStyle,
    color: Color,
    dates: List<String>,
) {
    if (dates.isEmpty()) return
    val lineHeight =
        measurer
            .measure("0", style = style)
            .size.height
            .toFloat()
    val bottomY = size.height - lineHeight
    val minText = "−1"
    drawLabel(measurer, style, color, "+1", 0f, 0f)
    drawLabel(measurer, style, color, "0", 0f, size.height / 2 - lineHeight / 2)
    drawLabel(measurer, style, color, minText, 0f, bottomY)
    val minWidth = measurer.measure(minText, style = style).size.width
    drawLabel(measurer, style, color, formatDate(dates.first()), minWidth + LABEL_GAP.toPx(), bottomY)
    drawLabelRight(measurer, style, color, formatDate(dates.last()), size.width, bottomY)
}

/** 차트 아래 범례. 터치 스크럽은 1차 범위 밖이라 각 계열의 마지막 값만 보여준다(사양 §6). */
@Composable
private fun ChartLegend(vararg items: Pair<String, String>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        items.forEach { (name, value) ->
            Text("$name $value", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- 판정 카드 ----

@Composable
private fun VerdictCard(report: Report) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("판정", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Text(riskFitText(report), style = MaterialTheme.typography.bodyMedium)
        Text(strategyFitText(report), style = MaterialTheme.typography.bodyMedium)
        Text(directionText(report.direction), style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * 위험 분리(판정 A) 문구. [Report.bands] 는 표시용 전체 밴드를 담고 있어, 판정에 실제로 쓰인
 * 두 밴드(표본 [Track.MIN_PER_BAND] 건 이상 중 서수가 가장 낮은/높은 밴드)를 이 함수가
 * 다시 골라낸다 — [Report] 는 그 두 밴드를 따로 내보내지 않는다.
 */
internal fun riskFitText(report: Report): String {
    val qualified = report.bands.filter { it.n >= Track.MIN_PER_BAND }.sortedBy { it.band.ordinal }
    if (qualified.size < 2 || report.riskFit == Fit.UNKNOWN) {
        return "위험 분리: 판정 불가(표본 ${Track.MIN_PER_BAND}건 이상인 밴드가 2개 미만)"
    }
    val low = qualified.first()
    val high = qualified.last()
    val verb = if (report.riskFit == Fit.MATCH) "맞음" else "어긋남"
    return "위험 분리: $verb(${low.band.label()} 변동성 ${pct0(low.vol)} > ${high.band.label()} ${pct0(high.vol)})"
}

/** 전략 가치(판정 B) 문구. [Track.simulate] 결과가 없으면(관측 부족) 숫자 없이 판정 불가만 알린다. */
internal fun strategyFitText(report: Report): String {
    val strategy = report.strategy
    if (strategy == null || report.strategyFit == Fit.UNKNOWN) return "전략 가치: 판정 불가(관측 부족)"
    val verb = if (report.strategyFit == Fit.MATCH) "맞음" else "어긋남"
    val tripped = strategy.tripwires.count { it.check == Check.TRIPPED }
    return "전략 가치: $verb · 최대낙폭 ${pct0(strategy.mdd)} vs 단순 보유 ${pct0(strategy.holdMdd)}, 폐기 조건 $tripped/${strategy.tripwires.size}"
}

/**
 * 방향 적중(판정 C) 문구. "모델의 주장이 아님"을 매번 붙이는 것은 적중률이 높아도 예측력을
 * 주장하지 않는다는 사양 §2 의 경계를 화면에서도 지키기 위해서다.
 */
internal fun directionText(direction: Double?): String {
    if (direction == null) return "방향 적중: 판정 불가(중립 밖 관측 없음) (모델의 주장이 아님)"
    return "방향 적중 ${pct0(direction)} (모델의 주장이 아님)"
}

// ---- 밴드별 표 ----

@Composable
private fun BandTable(report: Report) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("밴드별 표", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Band.entries.forEach { band -> BandRow(band, report.bands.find { it.band == band }) }
    }
}

@Composable
private fun BandRow(
    band: Band,
    stats: BandStats?,
) {
    val guide = guideStats(band)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(band.label(), style = MaterialTheme.typography.labelLarge)
            Text(bandCountText(stats), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BandStatLine("변동성", stats?.vol, guide.vol)
        BandStatLine("−10% 확률", stats?.drop10, guide.down10)
        BandStatLine("+10% 확률", stats?.rise10, guide.up10)
        BandStatLine("최악", stats?.worst, guide.worst3m)
    }
}

/** n 이 없으면(성숙 관측 자체가 없는 밴드) "-", [Track.MIN_PER_BAND] 미만이면 표본 부족. */
private fun bandCountText(stats: BandStats?): String =
    when {
        stats == null -> "-"
        stats.n < Track.MIN_PER_BAND -> Cause.SAMPLE.label()
        else -> "${stats.n}건"
    }

/** 실측(measured)과 검증값(guide)을 나란히. 실측이 없는 밴드는 "-"(사양 §6, 브리프 "모르면 지어내지 않는다"). */
@Composable
private fun BandStatLine(
    label: String,
    measured: Double?,
    guide: String,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(measured?.let(::pct0) ?: "-", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Text(guide, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
    }
}

// ---- 폐기 조건 점검 ----

@Composable
private fun TripwireSection(report: Report) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("폐기 조건 점검", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        val tripwires = report.strategy?.tripwires.orEmpty()
        if (tripwires.isEmpty()) {
            Text("판정 불가(관측 부족)", style = MaterialTheme.typography.bodyMedium)
        } else {
            tripwires.forEach { t -> Text(tripwireText(t), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** 폐기 조건 한 줄. [Check.TRIPPED] 가 "해당"(조건을 건드렸다는 뜻)이고 [Check.OK] 가 "미해당"이다. */
internal fun tripwireText(t: Tripwire): String {
    val verdict =
        when (t.check) {
            Check.OK -> "미해당"
            Check.TRIPPED -> "해당"
            Check.UNKNOWN -> "판정 불가"
        }
    return "${t.name}: $verdict · ${t.detail}"
}

// ---- 원인 분석 ----

@Composable
private fun DiagnosisSection(
    report: Report,
    retro: Boolean,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("원인 분석", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (report.gate.ok) {
            val (flagged, unflagged) = report.diagnoses.partition { it.flagged }
            flagged.forEach { d -> DiagnosisRow(d, retro) }
            if (unflagged.isNotEmpty()) {
                CollapsibleSection("해당 없음 ${unflagged.size}건") {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        unflagged.forEach { d -> DiagnosisRow(d, retro) }
                    }
                }
            }
        } else {
            Text(gateText(report.gate), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DiagnosisRow(
    d: Diagnosis,
    retro: Boolean,
) {
    Text("${d.cause.label()}: ${diagnosisText(d, retro)}", style = MaterialTheme.typography.bodyMedium)
}

/** §5 게이트 진행 표시. 리터럴 숫자 대신 [Track] 상수를 쓴다 — 상수가 바뀌면 문구도 같이 바뀐다. */
internal fun gateText(gate: Gate): String =
    "원인 분석까지: 성숙 관측 ${gate.matured}/${Track.MIN_MATURED} · 기간 ${gate.spanDays}/${Track.MIN_SPAN}일 · 밴드 ${gate.bands}/${Track.MIN_BANDS}"

/**
 * 원인 한 줄. 소급 모드에서 STALE(계산 지연)·UNIVERSE(유니버스 교체)는 "왜 해당 없음인지"를
 * 덧붙인다 — 둘 다 소급 [Obs] 를 만들 때 이 화면이 스스로 채운 값([retroObs])이라 실제로는
 * 판단할 수 없어서다(브리프 함정 2).
 */
internal fun diagnosisText(
    d: Diagnosis,
    retro: Boolean,
): String {
    if (!retro) return d.text
    return when (d.cause) {
        Cause.STALE -> d.text + " (소급 계산은 계산일이 관측일과 같아 해당 없음)"
        Cause.UNIVERSE -> d.text + " (소급 계산은 현재 유니버스 한 벌로 계산해 해당 없음)"
        else -> d.text
    }
}

/** §3 표의 원인 이름. 밴드별 표의 "표본 부족" 칸도 [Cause.SAMPLE] 의 이 라벨을 그대로 쓴다. */
internal fun Cause.label(): String =
    when (this) {
        Cause.NARROW -> "지수 쏠림"
        Cause.LAG -> "시차"
        Cause.PARTIAL -> "부분 창"
        Cause.BOUNDARY -> "경계 잡음"
        Cause.SUPPRESSED -> "실행 억제"
        Cause.NOT_APPLIED -> "미적용"
        Cause.UNIVERSE -> "유니버스 교체"
        Cause.STALE -> "계산 지연"
        Cause.SAMPLE -> "표본 부족"
    }

// ---- 각주 ----

@Composable
private fun FootnoteSection(
    retro: Boolean,
    universeAt: String,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        FootnoteText(FOOTER_1)
        FootnoteText(FOOTER_2)
        FootnoteText("069500 은 분배금을 반영하지 않은 가격 기준입니다.")
        FootnoteText("동일가중 평균은 현재 구성종목으로 계산해 생존 편향이 있습니다.")
        if (retro) {
            FootnoteText("소급 계산은 현재 유니버스(${formatDate(universeAt)} 기준)로 과거 신호를 다시 계산한 값입니다.")
        }
    }
}

@Composable
private fun FootnoteText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ---- 숫자·날짜 문구 ----

/** 정수 퍼센트. 음수는 BandGuide 의 정적 문구와 같은 U+2212 를 쓴다. NaN 은 "-"(지어내지 않는다). */
internal fun pct0(v: Double): String {
    if (v.isNaN()) return "-"
    val n = round(v * 100).toInt()
    return if (n < 0) "−${-n}%" else "$n%"
}

/** 배열의 마지막 정의된(NaN 이 아닌) 값. 하나도 없으면 NaN — 화면은 이걸 그대로 [pct0] 등에 넘긴다. */
private fun lastDefined(values: DoubleArray): Double = values.lastOrNull { !it.isNaN() } ?: Double.NaN

/** 069500·동일가중 평균의 정규화 레벨(첫날 100) 표시. 정수로 반올림해 보여준다. */
private fun levelText(v: Double): String = if (v.isNaN()) "-" else round(v).toInt().toString()

/** 상관계수 하나를 소수 둘째 자리까지. 퍼센트가 아니므로(비율이 아니다) [pct0] 을 쓰지 않는다. */
private fun corrText(v: Double): String {
    if (v.isNaN()) return "-"
    val cents = round(v * 100).toInt()
    val sign = if (cents < 0) "−" else ""
    val magnitude = abs(cents)
    val fraction = if (magnitude % 100 < 10) "0${magnitude % 100}" else "${magnitude % 100}"
    return "$sign${magnitude / 100}.$fraction"
}

/** yyyyMMdd 를 yyyy-MM-dd 로. 형식이 다르면(있을 수 없지만) 원본을 그대로 보여준다 — 지어내지 않는다. */
private fun formatDate(raw: String): String =
    runCatching { LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE).toString() }.getOrDefault(raw)
