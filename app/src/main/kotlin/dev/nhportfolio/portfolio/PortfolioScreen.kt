package dev.nhportfolio.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.nhportfolio.api.NhApi
import dev.nhportfolio.api.loadResult
import dev.nhportfolio.model.Account
import dev.nhportfolio.model.Balance
import dev.nhportfolio.model.Fill
import dev.nhportfolio.model.Holding
import dev.nhportfolio.store.cashKey
import dev.nhportfolio.store.readCashCodes
import dev.nhportfolio.store.readTargets
import dev.nhportfolio.store.targetsKey
import dev.nhportfolio.ui.BackIcon
import dev.nhportfolio.ui.RefreshIcon
import dev.nhportfolio.ui.barColors
import dev.nhportfolio.ui.bpPct
import dev.nhportfolio.ui.deltaChipColors
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
import kotlin.math.roundToInt

private const val FILL_DEBOUNCE_MS = 300L

/** 다이얼로그의 현금성 자산 토글. null 이면 그 자리에 아무것도 그리지 않는다. */
private data class CashToggle(
    val isCash: Boolean,
    val onClick: () -> Unit,
)

private const val FULL_BP = 10_000
private val BAR_WIDTH = 64.dp
private val TARGET_INPUT = Regex("""^\d{1,3}(\.\d{1,2})?$""")

/** [balance] 와 [error] 가 모두 null 이면 최초 로딩. 오류가 나도 마지막 정상 표는 유지한다. */
data class PortfolioUi(
    val balance: Balance? = null,
    val plan: Rebalance.Plan? = null,
    val lastFill: Fill? = null,
    val error: String? = null,
    /** 현금 행에 합쳐진 현금성 자산 개수. 0 이면 순수 예수금이다. */
    val cashAssets: Int = 0,
    val cashCodes: Set<String> = emptySet(),
)

class PortfolioViewModel(
    acctNo: String,
    private val api: NhApi,
    private val store: DataStore<Preferences>,
) : ViewModel() {
    private val account = Account(acctNo)
    private val targetsKey = targetsKey(acctNo)
    private val cashKey = cashKey(acctNo)
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
            store.data.map { readCashCodes(it, cashKey) }.catch { },
            lastFill,
        ) { (balance, error), targets, cashCodes, fill ->
            // 현금성 자산을 먼저 접어야 분모·비중·목표·매매 수량이 모두 같은 기준을 쓴다.
            val folded = balance?.let { Rebalance.foldCash(it, cashCodes) }
            PortfolioUi(
                balance = folded,
                plan = folded?.let { Rebalance.plan(it, targets) },
                lastFill = fill,
                error = error,
                cashAssets = balance?.holdings.orEmpty().count { it.code in cashCodes },
                cashCodes = cashCodes,
            )
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

    /**
     * 목표 합계를 정확히 100% 로 맞춘다. 예수금 목표가 있으면 그 값은 그대로 두고
     * 종목만 남은 자리를 채운다 — 사용자가 정한 현금 비중을 말없이 바꾸지 않는다.
     */
    fun normalizeTargets() {
        edit { current -> Rebalance.normalize(current, currentWeightsBp()) }
    }

    /**
     * 종목을 현금성 자산으로 묶거나 되돌린다. 묶으면 평가금액이 현금에 합쳐지고
     * 보유 목록에서 빠지므로, 남아 있던 목표 비중도 함께 지운다 — 목록에 없는 종목의
     * 목표가 남으면 합계만 어긋나고 화면 어디에도 보이지 않는다.
     */
    fun toggleCashAsset(code: String) {
        viewModelScope.launch {
            store.edit { prefs ->
                val current = readCashCodes(prefs, cashKey)
                val next = if (code in current) current - code else current + code
                prefs[cashKey] = Json.encodeToString(next)
                if (code !in current) {
                    prefs[targetsKey] = Json.encodeToString(readTargets(prefs, targetsKey) - code)
                }
            }
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

/** 현금성 자산이 합쳐졌으면 "예수금" 이 아니라 "현금" 이다 — 이름이 내용과 어긋나면 안 된다. */
private fun cashLabel(cashAssets: Int): String = if (cashAssets > 0) "현금" else "예수금"

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
    var confirmClear by remember { mutableStateOf(false) }

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
                title = { Text(acctNo, style = MaterialTheme.typography.titleSmall) },
                navigationIcon = {
                    IconButton(onClick = onBack) { BackIcon() }
                },
                actions = {
                    IconButton(onClick = vm::refresh) { RefreshIcon() }
                },
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
                        SummaryCard(plan, ui.cashAssets) { vm.normalizeTargets() }
                        ModeSelector(rebalanceMode, balance.holdings.size) { rebalanceMode = it }
                        SelectAllBar(plan, rebalanceMode, selected) { selected = it }
                        HoldingsList(
                            balance = balance,
                            plan = plan,
                            rebalanceMode = rebalanceMode,
                            selected = selected,
                            cashLabel = cashLabel(ui.cashAssets),
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
                                onClearTargets = { confirmClear = true },
                                onSet = { editing = selected.toList() to null },
                            )
                        }
                    }
                }
            }
        }
    }

    ClearTargetsDialog(
        count = selected.size.takeIf { confirmClear },
        onDismiss = { confirmClear = false },
        onConfirm = {
            vm.setTargets(selected.toList(), null)
            confirmClear = false
            selected = emptySet()
        },
    )

    editing?.let { (codes, currentBp) ->
        TargetEditor(
            codes = codes,
            currentBp = currentBp,
            holdings = ui.balance?.holdings.orEmpty(),
            cashCodes = ui.cashCodes,
            onToggleCash = { code ->
                vm.toggleCashAsset(code)
                editing = null
            },
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
    cashCodes: Set<String>,
    onToggleCash: (String) -> Unit,
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
    // 현금성 지정은 단건, 그것도 실제 종목에만 — 예수금 행이나 일괄 편집에는 뜻이 없다.
    val cashToggle =
        single
            ?.takeIf { it != Rebalance.CASH }
            ?.let { CashToggle(isCash = it in cashCodes, onClick = { onToggleCash(it) }) }
    TargetDialog(
        name = name,
        currentBp = currentBp,
        cashToggle = cashToggle,
        onDismiss = onDismiss,
        onSet = onSet,
    )
}

/**
 * 리밸런스 탭 머리줄. 전체 선택·해제와 현재 선택 수를 보여준다.
 *
 * 일부만 골랐을 때는 [ToggleableState.Indeterminate] 로 두고, 누르면 전체 선택으로 간다 —
 * 체크박스 관례를 그대로 따른다. 예수금은 [total] 에서 빠져 있다(비례 조정 경로를 타야 한다).
 */
@Composable
private fun SelectAllBar(
    plan: Rebalance.Plan,
    rebalanceMode: Boolean,
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
) {
    if (!rebalanceMode) return
    val selectable = remember(plan) { plan.lines.filter { it.code != Rebalance.CASH }.map { it.code } }
    // 잔고가 바뀌어 사라진 종목은 선택에서 뺀다 — 없는 종목에 목표를 쓰면 합계만 어긋나고
    // 화면 어디에도 보이지 않는다.
    val stale = selected - selectable.toSet()
    if (stale.isNotEmpty()) onSelectedChange(selected - stale)

    val total = selectable.size
    val selectedCount = selectable.count { it in selected }
    val onToggleAll = { onSelectedChange(if (total > 0 && selectedCount == total) emptySet() else selectable.toSet()) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = total > 0, onClick = onToggleAll)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(
            state =
                when {
                    total > 0 && selectedCount == total -> ToggleableState.On
                    selectedCount == 0 -> ToggleableState.Off
                    else -> ToggleableState.Indeterminate
                },
            onClick = onToggleAll,
            enabled = total > 0,
        )
        Text("전체 선택", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Text("$selectedCount / $total", style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
}

/** 여러 종목을 고른 동안만 뜨는 하단 바. 입력한 값은 고른 종목 '각각' 의 목표가 된다. */
@Composable
private fun BatchBar(
    count: Int,
    onClear: () -> Unit,
    onClearTargets: () -> Unit,
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
                // "해제" 는 선택 해제인지 목표 해제인지 헷갈린다 — 둘을 또렷이 갈라 쓴다.
                TextButton(onClick = onClear) { Text("선택 취소") }
                TextButton(onClick = onClearTargets) { Text("목표 지우기") }
                TextButton(onClick = onSet) { Text("목표 설정") }
            }
        }
    }
}

/**
 * 목표 일괄 삭제 확인. [count] 가 null 이면 아무것도 그리지 않는다.
 *
 * 되돌리기가 없는 앱이고 "전체 선택 -> 목표 지우기" 는 두 번의 탭으로 공들여 잡은 목표를
 * 전부 날린다. 설정은 확인 없이 두고 삭제에만 한 번 묻는다.
 */
@Composable
private fun ClearTargetsDialog(
    count: Int?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (count == null) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("목표 지우기") },
        text = { Text("선택한 ${count}개 종목의 목표 비중을 지웁니다. 되돌릴 수 없습니다.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("지우기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}

@Composable
private fun SummaryCard(
    plan: Rebalance.Plan,
    cashAssets: Int,
    onNormalize: () -> Unit,
) {
    // 카드 테두리를 걷어내고 총액을 화면에서 가장 큰 글자로 둔다 — 먼저 읽히는 숫자가 총액이다.
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 15.dp, bottom = 13.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Column {
            Text(
                "총 평가금액",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(plan.total.krw(), style = MaterialTheme.typography.displaySmall)
            // 총액 바로 아래 평가손익 — 계좌를 열었을 때 두 번째로 찾는 숫자다.
            if (plan.totalPl != 0L) {
                Text(
                    "${if (plan.totalPl > 0) "+" else ""}${plan.totalPl.krw()} (${plan.totalPlRate.pct()})",
                    style = MaterialTheme.typography.titleSmall,
                    color = plColor(plan.totalPlRate),
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatBox(
                label = if (cashAssets > 0) "현금 (예수금 + 현금성 ${cashAssets}건)" else "현금 (D+2)",
                value =
                    plan.lines
                        .last()
                        .currentAmt
                        .krw(),
                modifier = Modifier.weight(1f),
            )
            StatBox(
                label = "목표 합계",
                value = plan.targetSumBp.takeIf { it > 0 }?.bpPct() ?: "미설정",
                valueColor =
                    when {
                        plan.targetSumBp > FULL_BP -> MaterialTheme.colorScheme.error
                        plan.targetSumBp == FULL_BP -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                modifier = Modifier.weight(1f),
            )
        }
        PlanWarnings(plan, onNormalize)
    }
}

/** 목표 합계가 100% 가 아니거나 예수금이 모자랄 때만 뜨는 안내. 평소에는 아무것도 그리지 않는다. */
@Composable
private fun PlanWarnings(
    plan: Rebalance.Plan,
    onNormalize: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val sum = plan.targetSumBp
        // 정확히 100% 면 경고도 버튼도 없다 — 합계 자체는 위 StatBox 가 이미 보여준다.
        if (sum > 0 && sum != FULL_BP) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text =
                        when {
                            sum > FULL_BP -> "100% 를 넘습니다"
                            else -> "100% 에 미달합니다"
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (sum > FULL_BP) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                TextButton(onClick = onNormalize) { Text("100%로 맞추기") }
            }
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

/** 요약 카드의 작은 수치 상자. 라벨은 작게, 숫자는 굵게. */
@Composable
private fun StatBox(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = valueColor)
    }
}

@Composable
private fun ModeSelector(
    rebalanceMode: Boolean,
    holdingCount: Int,
    onChange: (Boolean) -> Unit,
) {
    // 알약 세그먼트 대신 밑줄 탭 — 테두리가 사라지는 만큼 표에 자리가 남고, 상용 증권앱의 관례다.
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                ModeTab("보유", !rebalanceMode) { onChange(false) }
                ModeTab("리밸런스", rebalanceMode) { onChange(true) }
            }
            Text(
                "${holdingCount}종목",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        HorizontalDivider()
    }
}

@Composable
private fun ModeTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // IntrinsicSize.Max 로 열 너비를 글자에 묶는다 — 안 그러면 밑줄의 fillMaxWidth 가
    // 부모의 남은 폭을 통째로 가져가 첫 탭만 길게 늘어난다.
    Column(
        Modifier.clickable(onClick = onClick).width(IntrinsicSize.Max).padding(top = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.5.dp)
                .background(if (selected) MaterialTheme.colorScheme.onSurface else Color.Transparent),
        )
    }
}

@Composable
private fun HoldingsList(
    balance: Balance,
    plan: Rebalance.Plan,
    rebalanceMode: Boolean,
    selected: Set<String>,
    cashLabel: String,
    onEdit: (code: String, currentBp: Int?) -> Unit,
    onToggle: (code: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val byCode = remember(balance) { balance.holdings.associateBy { it.code } }
    // 막대 눈금은 가장 큰 비중/목표 기준. 절대 눈금이면 작은 종목이 실선이 되어 못 읽는다.
    val scaleBp = remember(plan) { plan.lines.maxOf { maxOf(it.weightBp, it.targetBp ?: 0) }.coerceAtLeast(1) }
    LazyColumn(modifier.fillMaxWidth()) {
        items(plan.lines) { line ->
            HoldingRow(
                line = line,
                holding = byCode[line.code],
                cashLabel = cashLabel,
                rebalanceMode = rebalanceMode,
                checked = line.code in selected,
                scaleBp = scaleBp,
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
    cashLabel: String,
    rebalanceMode: Boolean,
    scaleBp: Int,
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
                Text(holding?.name ?: cashLabel, style = MaterialTheme.typography.titleMedium)
                Text(line.currentAmt.krw(), style = MaterialTheme.typography.titleMedium)
            }
            HoldingDetail(line, holding, scaleBp)
        }
    }
}

/** 이름·금액 아래. 보유 명세와 목표·매매 수량을 함께 보여준다. */
@Composable
private fun HoldingDetail(
    line: Rebalance.Line,
    holding: Holding?,
    scaleBp: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        // 주식수·평균매입가·현재가·잔고수량은 요구 항목이라 늘 보여준다.
        // maxLines 를 걸지 않는다 — 폰트 배율이 크면 줄바꿈되지만, 잘라내면 요구 데이터가 사라진다.
        if (holding != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "보유 ${holding.qty.shares()}주 · 잔고 ${holding.remainQty.shares()}주 · " +
                        "평균 ${holding.avgPrice.krw()} · 현재 ${holding.price.krw()}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    holding.pnlRate.pct(),
                    color = plColor(holding.pnlRate),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 3.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WeightBar(line.weightBp, line.targetBp, scaleBp)
            Text(weightArrow(line), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            // 목표가 없으면 칩을 띄우지 않는다 — "—" 로 채우면 진짜 할 일이 묻힌다.
            if (line.targetBp != null) DeltaChip(line.deltaShares)
        }
    }
}

/** 목표가 있으면 `현재 → 목표`, 없으면 현재 비중만. 예수금 행도 같은 표기를 쓴다. */
private fun weightArrow(line: Rebalance.Line): String =
    line.targetBp?.let { "${line.weightBp.bpPct()} → ${it.bpPct()}" } ?: line.weightBp.bpPct()

/**
 * 사야 할/팔아야 할 주식 수. 수익률과 같은 빨강·파랑을 쓰되 **채운 칩**이라 형태로 갈린다 —
 * 한 행에 같은 색이 둘이어도 섞이지 않고, 색만으로 구분하지 않으니 색각 이상에서도 읽힌다.
 *
 * 유지·계산 불가는 무채색이다. 아무 일도 없는데 강조하면 진짜 할 일이 묻힌다.
 */
@Composable
private fun DeltaChip(delta: Long?) {
    val c = deltaChipColors(delta)
    Text(
        text =
            when (delta) {
                null -> "—"
                0L -> "유지"
                else -> if (delta > 0) "${delta.shares()}주 매수" else "${(-delta).shares()}주 매도"
            },
        style = MaterialTheme.typography.labelLarge,
        color = c.ink,
        modifier =
            Modifier
                .background(c.surface, MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/**
 * 현재 비중과 목표 비중의 거리를 막대 하나로 보여준다. 두 숫자를 읽고 빼는 대신 눈으로 잰다.
 *
 * 눈금은 **가장 큰 목표를 100% 로 잡은 상대 척도**다. 절대 눈금(0~100%)이면 비중 10% 종목이
 * 실선이 되어 못 읽는다. 척도가 상대적이라는 사실은 목표선이 알려 준다 — 채움이 선을 넘으면
 * 초과, 못 미치면 미달. 목표가 없으면 선을 긋지 않는다.
 */
@Composable
private fun WeightBar(
    weightBp: Int,
    targetBp: Int?,
    scaleBp: Int,
) {
    val c = barColors()
    val scale = scaleBp.coerceAtLeast(1).toFloat()
    // 목표선이 막대 위아래로 조금 삐져나와야 눈에 걸린다 — 그래서 트랙보다 컨테이너가 높다.
    Box(Modifier.width(BAR_WIDTH).height(13.dp), contentAlignment = Alignment.CenterStart) {
        Box(Modifier.fillMaxWidth().height(7.dp).background(c.surface, CircleShape))
        Box(
            Modifier
                .fillMaxWidth((weightBp / scale).coerceIn(0f, 1f))
                .height(7.dp)
                .background(c.ink, CircleShape),
        )
        targetBp?.let { t ->
            Box(
                Modifier.fillMaxWidth((t / scale).coerceIn(0f, 1f)).fillMaxHeight(),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface))
            }
        }
    }
}

@Composable
private fun TargetDialog(
    name: String,
    currentBp: Int?,
    cashToggle: CashToggle?,
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
                if (cashToggle != null) {
                    TextButton(onClick = cashToggle.onClick, modifier = Modifier.padding(top = 8.dp)) {
                        Text(if (cashToggle.isCash) "현금성 자산에서 빼기" else "현금성 자산으로 묶기")
                    }
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
