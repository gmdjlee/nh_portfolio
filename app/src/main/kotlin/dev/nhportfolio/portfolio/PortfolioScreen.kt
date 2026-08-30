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

    /** [bp] 가 null 이면 목표를 지운다. 범위 밖 값은 호출 전에 걸러진다. */
    fun setTarget(
        code: String,
        bp: Int?,
    ) {
        require(bp == null || bp in 0..FULL_BP) { "목표 비중은 0~100% 범위여야 합니다" }
        viewModelScope.launch {
            store.edit { prefs ->
                val next = readTargets(prefs, targetsKey).toMutableMap()
                if (bp == null) next -= code else next[code] = bp
                prefs[targetsKey] = Json.encodeToString(next)
            }
        }
    }
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
    var editing by remember { mutableStateOf<Pair<String, Int?>?>(null) }

    LaunchedEffect(ui.lastFill) {
        ui.lastFill?.let { fill ->
            val at =
                fill.time
                    .takeIf { it.length >= 4 }
                    ?.take(4)
                    ?.chunked(2)
                    ?.joinToString(":")
                    ?.let { " ($it)" }
                    .orEmpty()
            snackbar.showSnackbar("${fill.name} ${fill.qty.shares()}주 체결 @${fill.price.krw()}$at")
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
                            onEdit = { code, bp -> editing = code to bp },
                        )
                    }
                }
            }
        }
    }

    editing?.let { (code, currentBp) ->
        val name =
            ui.balance
                ?.holdings
                ?.firstOrNull { it.code == code }
                ?.name
                ?: if (code == Rebalance.CASH) "예수금" else code
        TargetDialog(
            name = name,
            currentBp = currentBp,
            onDismiss = { editing = null },
            onSet = { bp ->
                vm.setTarget(code, bp)
                editing = null
            },
        )
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
    onEdit: (code: String, currentBp: Int?) -> Unit,
) {
    val byCode = remember(balance) { balance.holdings.associateBy { it.code } }
    LazyColumn(Modifier.fillMaxSize()) {
        items(plan.lines) { line ->
            val holding = byCode[line.code]
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onEdit(line.code, line.targetBp) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(holding?.name ?: "예수금", style = MaterialTheme.typography.titleMedium)
                    Text(line.currentAmt.krw(), style = MaterialTheme.typography.titleMedium)
                }
                if (rebalanceMode) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "${line.weightBp.bpPct()} → ${line.targetBp?.bpPct() ?: "목표 없음"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(
                            text =
                                when (val delta = line.deltaShares) {
                                    null -> "—"
                                    0L -> "유지"
                                    else -> if (delta > 0) "${delta.shares()}주 매수" else "${(-delta).shares()}주 매도"
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            color =
                                when {
                                    (line.deltaShares ?: 0L) > 0 -> MaterialTheme.colorScheme.primary
                                    (line.deltaShares ?: 0L) < 0 -> MaterialTheme.colorScheme.secondary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                        )
                    }
                } else if (holding != null) {
                    Text(
                        "보유 ${holding.qty.shares()}주 · 잔고 ${holding.remainQty.shares()}주 · " +
                            "평균 ${holding.avgPrice.krw()} · 현재 ${holding.price.krw()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(holding.pnlRate.pct(), color = plColor(holding.pnlRate), style = MaterialTheme.typography.bodyMedium)
                        Text("비중 ${line.weightBp.bpPct()}", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text("비중 ${line.weightBp.bpPct()}", style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
        }
    }
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
