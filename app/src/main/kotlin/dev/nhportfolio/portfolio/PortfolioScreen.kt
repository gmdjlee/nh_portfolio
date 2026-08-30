package dev.nhportfolio.portfolio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.nhportfolio.api.NhApi
import dev.nhportfolio.api.loadResult
import dev.nhportfolio.model.Account
import dev.nhportfolio.model.Balance
import dev.nhportfolio.model.Fill
import dev.nhportfolio.model.Holding
import dev.nhportfolio.ui.bpPct
import dev.nhportfolio.ui.krw
import dev.nhportfolio.ui.pct
import dev.nhportfolio.ui.plColor
import dev.nhportfolio.ui.shares
import dev.nhportfolio.ui.userMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import java.security.MessageDigest
import kotlin.math.roundToInt

private const val FILL_DEBOUNCE_MS = 300L
private const val FULL_BP = 10_000
private val TARGET_INPUT = Regex("""^\d{1,3}(\.\d{1,2})?$""")

/** [balance] 와 [error] 가 모두 null 이면 최초 로딩. 오류가 나도 마지막 정상 표는 유지한다. */
data class PortfolioUi(
    val balance: Balance? = null,
    val plan: Rebalance.Plan? = null,
    val lastFill: Fill? = null,
    val error: String? = null,
)

class PortfolioViewModel(
    acctNo: String,
    private val api: NhApi,
    private val store: DataStore<Preferences>,
) : ViewModel() {
    private val account = Account(acctNo)
    private val targetsKey = targetsKey(acctNo)
    private val kick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val lastFill = MutableStateFlow<Fill?>(null)

    // 재조회는 사용자 범위의 **모든** 체결통보가 트리거한다(토큰에 묶인 채널이라 계좌 필터가 필요 없다).
    // 계좌 매칭은 스낵바 표시에만 쓰므로 accountno 형식이 달라도 기능이 죽지 않는다.
    private val fills =
        api.fills().onEach { fill ->
            if (fill.acctNo.filter(Char::isDigit) == acctNo.filter(Char::isDigit)) lastFill.value = fill
        }

    private val loads =
        merge(flowOf(Unit), kick, fills.map { }.debounce(FILL_DEBOUNCE_MS))
            .mapLatest { loadResult { api.balance(account) } }
            .runningFold(null as Balance? to null as String?) { (last, _), result ->
                result.fold({ it to null }, { last to it.userMessage() })
            }.drop(1)

    val ui: StateFlow<PortfolioUi> =
        combine(
            loads,
            store.data.map { readTargets(it, targetsKey) }.catch { },
            lastFill,
        ) { (balance, error), targets, fill ->
            PortfolioUi(balance, balance?.let { Rebalance.plan(it, targets) }, fill, error)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortfolioUi())

    fun refresh() {
        kick.tryEmit(Unit)
    }

    /** 스낵바로 보여준 체결을 소비한다 — 안 그러면 화면 재진입(잠금 해제·뒤로가기)마다 다시 뜬다. */
    fun consumeFill() {
        lastFill.value = null
    }

    /**
     * [bp] 가 null 이면 목표를 지운다. 범위 밖 값은 호출 전에 걸러진다.
     *
     * 예수금에 목표를 주면 종목들을 비례 조정해 자리를 만든다([Rebalance.scaleForCash]).
     * 예수금 목표를 지울 때는 조정하지 않는다 — 맞춰야 할 자리가 없어진 것뿐이다.
     */
    fun setTarget(
        code: String,
        bp: Int?,
    ) {
        require(bp == null || bp in 0..FULL_BP) { "목표 비중은 0~100% 범위여야 합니다" }
        edit { current ->
            when {
                // 목표를 안 잡은 종목도 현재 비중을 기준으로 조정안을 받아야 한다.
                code == Rebalance.CASH && bp != null -> Rebalance.scaleForCash(current, bp, currentWeightsBp())

                bp == null -> current - code

                else -> current + (code to bp)
            }
        }
    }

    /**
     * 여러 종목에 같은 목표를 한 번에 준다. [bp] 가 null 이면 모두 지운다.
     *
     * 낱개로 [setTarget] 을 반복 호출하면 안 된다 — 각 호출이 자기 시점의 맵을 읽어 쓰므로
     * 마지막 것만 남는다. 한 번의 edit 안에서 전부 반영한다.
     */
    fun setTargets(
        codes: Collection<String>,
        bp: Int?,
    ) {
        require(bp == null || bp in 0..FULL_BP) { "목표 비중은 0~100% 범위여야 합니다" }
        require(Rebalance.CASH !in codes) { "예수금은 일괄 설정 대상이 아니다 — scaleForCash 를 타야 한다" }
        if (codes.isEmpty()) return
        edit { current ->
            if (bp == null) current - codes.toSet() else current + codes.associateWith { bp }
        }
    }

    /** 마지막으로 계산된 종목별 현재 비중. 아직 잔고를 못 받았으면 비어 있다. */
    private fun currentWeightsBp(): Map<String, Int> =
        ui.value.plan
            ?.lines
            .orEmpty()
            .filter { it.code != Rebalance.CASH }
            .associate { it.code to it.weightBp }

    private fun edit(transform: (Map<String, Int>) -> Map<String, Int>) {
        viewModelScope.launch {
            store.edit { prefs ->
                prefs[targetsKey] = Json.encodeToString(transform(readTargets(prefs, targetsKey)))
            }
        }
    }
}

/** 체결 스낵바 문구. `conctime` 이 비었거나 짧으면 괄호째 생략한다 — "()" 만 남으면 흉하다. */
private fun fillMessage(fill: Fill): String {
    val at =
        fill.time
            .takeIf { it.length >= 4 }
            ?.take(4)
            ?.chunked(2)
            ?.joinToString(":")
            ?.let { " ($it)" }
            .orEmpty()
    return "${fill.name} ${fill.qty.shares()}주 체결 @${fill.price.krw()}$at"
}

/** 계좌번호를 키 이름으로 노출하지 않는다 — 목표값 자체는 평문이다. */
private fun targetsKey(acctNo: String): Preferences.Key<String> {
    val digest = MessageDigest.getInstance("SHA-256").digest(acctNo.toByteArray())
    return stringPreferencesKey("targets_" + digest.joinToString("") { "%02x".format(it) }.take(16))
}

/** 저장값이 깨졌거나 범위를 벗어나도 화면이 죽지 않는다. */
private fun readTargets(
    prefs: Preferences,
    key: Preferences.Key<String>,
): Map<String, Int> =
    runCatching { Json.decodeFromString<Map<String, Int>>(prefs[key] ?: "{}") }
        .getOrDefault(emptyMap())
        .filterValues { it in 0..FULL_BP }

@Composable
fun PortfolioScreen(
    acctNo: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    vm: PortfolioViewModel = koinViewModel { parametersOf(acctNo) },
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var rebalanceMode by remember { mutableStateOf(false) }

    /** 편집 대상 종목코드들. 한 개면 단건, 여러 개면 일괄. 예수금은 언제나 단건이다. */
    var editing by remember { mutableStateOf<Pair<List<String>, Int?>?>(null) }
    var selected by remember { mutableStateOf(emptySet<String>()) }

    // 보유 탭으로 나가면 선택은 의미가 없다 — 남겨 두면 다시 들어왔을 때 놀란다.
    if (!rebalanceMode && selected.isNotEmpty()) selected = emptySet()

    LaunchedEffect(ui.lastFill) {
        ui.lastFill?.let { fill ->
            snackbar.showSnackbar(fillMessage(fill))
            vm.consumeFill()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(acctNo) },
                navigationIcon = { TextButton(onClick = onBack) { Text("뒤로") } },
                actions = { TextButton(onClick = vm::refresh) { Text("새로고침") } },
            )
        },
    ) { padding ->
        val balance = ui.balance
        val plan = ui.plan
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                balance == null && ui.error != null -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(ui.error.orEmpty())
                        TextButton(onClick = vm::refresh) { Text("다시 시도") }
                    }
                }

                balance == null || plan == null -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                else -> {
                    Column(Modifier.fillMaxSize()) {
                        ui.error?.let { message ->
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        SummaryCard(plan)
                        ModeSelector(rebalanceMode) { rebalanceMode = it }
                        HoldingsList(
                            balance = balance,
                            plan = plan,
                            rebalanceMode = rebalanceMode,
                            selected = selected,
                            onEdit = { code, bp -> editing = listOf(code) to bp },
                            onToggle = { code ->
                                selected = if (code in selected) selected - code else selected + code
                            },
                            modifier = Modifier.weight(1f),
                        )
                        if (selected.isNotEmpty()) {
                            BatchBar(
                                count = selected.size,
                                onClear = { selected = emptySet() },
                                onSet = { editing = selected.toList() to null },
                            )
                        }
                    }
                }
            }
        }
    }

    editing?.let { (codes, currentBp) ->
        TargetEditor(
            codes = codes,
            currentBp = currentBp,
            holdings = ui.balance?.holdings.orEmpty(),
            onDismiss = { editing = null },
            onSet = { bp ->
                val single = codes.singleOrNull()
                if (single != null) vm.setTarget(single, bp) else vm.setTargets(codes, bp)
                editing = null
                selected = emptySet()
            },
        )
    }
}

/** 단건이면 종목 이름을, 일괄이면 개수를 제목에 쓴다. */
@Composable
private fun TargetEditor(
    codes: List<String>,
    currentBp: Int?,
    holdings: List<Holding>,
    onDismiss: () -> Unit,
    onSet: (Int?) -> Unit,
) {
    val single = codes.singleOrNull()
    val name =
        when {
            single == null -> "선택한 ${codes.size}개 종목"
            single == Rebalance.CASH -> "예수금"
            else -> holdings.firstOrNull { it.code == single }?.name ?: single
        }
    TargetDialog(name = name, currentBp = currentBp, onDismiss = onDismiss, onSet = onSet)
}

/** 여러 종목을 고른 동안만 뜨는 하단 바. 입력한 값은 고른 종목 '각각' 의 목표가 된다. */
@Composable
private fun BatchBar(
    count: Int,
    onClear: () -> Unit,
    onSet: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${count}개 선택", style = MaterialTheme.typography.bodyMedium)
            Row {
                TextButton(onClick = onClear) { Text("해제") }
                TextButton(onClick = onSet) { Text("목표 비중 설정") }
            }
        }
    }
}

@Composable
private fun SummaryCard(plan: Rebalance.Plan) {
    Card(Modifier.fillMaxWidth().padding(16.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("총 평가", style = MaterialTheme.typography.bodySmall)
            Text(plan.total.krw(), style = MaterialTheme.typography.titleMedium)
            Text("예수금(D+2) ${plan.lines.last().currentAmt.krw()}", style = MaterialTheme.typography.bodySmall)

            val sum = plan.targetSumBp
            if (sum > 0) {
                Text(
                    text =
                        when {
                            sum > FULL_BP -> "목표 합계 ${sum.bpPct()} — 100% 를 넘습니다"
                            sum < FULL_BP -> "목표 합계 ${sum.bpPct()} — 100% 에 미달합니다"
                            else -> "목표 합계 ${sum.bpPct()}"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sum > FULL_BP) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (plan.cashAfter < 0) {
                Text(
                    "매매 후 예수금 ${plan.cashAfter.krw()} — 예수금이 부족합니다",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ModeSelector(
    rebalanceMode: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        SegmentedButton(
            selected = !rebalanceMode,
            onClick = { onChange(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
        ) { Text("보유") }
        SegmentedButton(
            selected = rebalanceMode,
            onClick = { onChange(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
        ) { Text("리밸런스") }
    }
}

@Composable
private fun HoldingsList(
    balance: Balance,
    plan: Rebalance.Plan,
    rebalanceMode: Boolean,
    selected: Set<String>,
    onEdit: (code: String, currentBp: Int?) -> Unit,
    onToggle: (code: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val byCode = remember(balance) { balance.holdings.associateBy { it.code } }
    LazyColumn(modifier.fillMaxWidth()) {
        items(plan.lines) { line ->
            HoldingRow(
                line = line,
                holding = byCode[line.code],
                rebalanceMode = rebalanceMode,
                checked = line.code in selected,
                onEdit = { onEdit(line.code, line.targetBp) },
                onToggle = { onToggle(line.code) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun HoldingRow(
    line: Rebalance.Line,
    holding: Holding?,
    rebalanceMode: Boolean,
    checked: Boolean,
    onEdit: () -> Unit,
    onToggle: () -> Unit,
) {
    // 예수금은 비례 조정 로직을 타야 하므로 일괄 선택 대상이 아니다.
    val selectable = rebalanceMode && line.code != Rebalance.CASH
    Row(
        Modifier.fillMaxWidth().clickable { onEdit() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectable) {
            Checkbox(
                checked = checked,
                onCheckedChange = { onToggle() },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .padding(
                    start = if (selectable) 4.dp else 20.dp,
                    end = 20.dp,
                    top = 12.dp,
                    bottom = 12.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(holding?.name ?: "예수금", style = MaterialTheme.typography.titleMedium)
                Text(line.currentAmt.krw(), style = MaterialTheme.typography.titleMedium)
            }
            HoldingDetail(line, holding, rebalanceMode)
        }
    }
}

/** 이름·금액 아래 한 줄. 리밸런스 탭이면 목표와 매매 수량, 아니면 보유 명세를 보여준다. */
@Composable
private fun HoldingDetail(
    line: Rebalance.Line,
    holding: Holding?,
    rebalanceMode: Boolean,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (rebalanceMode) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(weightArrow(line), style = MaterialTheme.typography.bodySmall)
                DeltaText(line.deltaShares)
            }
        } else if (holding != null) {
            Text(
                "보유 ${holding.qty.shares()}주 · 잔고 ${holding.remainQty.shares()}주 · " +
                    "평균 ${holding.avgPrice.krw()} · 현재 ${holding.price.krw()}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(holding.pnlRate.pct(), color = plColor(holding.pnlRate), style = MaterialTheme.typography.bodyMedium)
                Text("비중 ${weightArrow(line)}", style = MaterialTheme.typography.bodySmall)
            }
            // 목표가 있을 때만 한 줄 더 쓴다 — 목표 없는 종목까지 "—" 로 채우면 표가 시끄럽다.
            if (line.targetBp != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    DeltaText(line.deltaShares)
                }
            }
        } else {
            Text("비중 ${weightArrow(line)}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** 목표가 있으면 `현재 → 목표`, 없으면 현재 비중만. 예수금 행도 같은 표기를 쓴다. */
private fun weightArrow(line: Rebalance.Line): String =
    line.targetBp?.let { "${line.weightBp.bpPct()} → ${it.bpPct()}" } ?: line.weightBp.bpPct()

/** 사야 할/팔아야 할 주식 수. 매수는 primary, 매도는 secondary 로 물들인다. */
@Composable
private fun DeltaText(delta: Long?) {
    Text(
        text =
            when (delta) {
                null -> "—"
                0L -> "유지"
                else -> if (delta > 0) "${delta.shares()}주 매수" else "${(-delta).shares()}주 매도"
            },
        style = MaterialTheme.typography.bodyMedium,
        color =
            when {
                (delta ?: 0L) > 0 -> MaterialTheme.colorScheme.primary
                (delta ?: 0L) < 0 -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
    )
}

@Composable
private fun TargetDialog(
    name: String,
    currentBp: Int?,
    onDismiss: () -> Unit,
    onSet: (Int?) -> Unit,
) {
    var text by remember {
        mutableStateOf(
            currentBp
                ?.let { bp ->
                    (bp / 100.0).let {
                        if (it % 1.0 ==
                            0.0
                        ) {
                            it.toInt().toString()
                        } else {
                            it.toString()
                        }
                    }
                }.orEmpty(),
        )
    }
    val parsedBp =
        text
            .trim()
            .takeIf { it.matches(TARGET_INPUT) }
            ?.toDouble()
            ?.let { (it * 100).roundToInt() }
            ?.takeIf { it in 0..FULL_BP }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("$name 목표 비중") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("퍼센트 (0 ~ 100)") },
                    singleLine = true,
                    isError = text.isNotBlank() && parsedBp == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                if (text.isNotBlank() && parsedBp == null) {
                    Text("0 ~ 100 사이 숫자를 입력하세요", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = parsedBp != null, onClick = { onSet(parsedBp) }) { Text("저장") }
        },
        dismissButton = {
            Row {
                if (currentBp != null) TextButton(onClick = { onSet(null) }) { Text("목표 삭제") }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
}
