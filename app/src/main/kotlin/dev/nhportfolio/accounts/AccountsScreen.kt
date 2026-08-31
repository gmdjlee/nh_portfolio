package dev.nhportfolio.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import dev.nhportfolio.portfolio.Rebalance
import dev.nhportfolio.security.Vault
import dev.nhportfolio.store.cashKey
import dev.nhportfolio.store.nameKey
import dev.nhportfolio.store.readCashCodes
import dev.nhportfolio.ui.PencilIcon
import dev.nhportfolio.ui.SettingsIcon
import dev.nhportfolio.ui.compositionColors
import dev.nhportfolio.ui.krw
import dev.nhportfolio.ui.pct
import dev.nhportfolio.ui.plColor
import dev.nhportfolio.ui.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

private val BAR_HEIGHT = 8.dp

/** [summary] 가 null 이면 아직 조회 중이다. 목록은 요약을 기다리지 않고 먼저 그린다. */
data class AccountRow(
    val no: String,
    val name: String?,
    val summary: Result<Balance>?,
) {
    val plan get() = summary?.getOrNull()?.let { Rebalance.plan(it, emptyMap()) }
}

/** [rows] 가 null 이면 계좌 목록 자체가 아직 없다(로딩 또는 [error]). */
data class AccountsUi(
    val rows: List<AccountRow>? = null,
    val error: String? = null,
)

class AccountsViewModel(
    vault: Vault,
    private val api: NhApi,
    private val store: DataStore<Preferences>,
) : ViewModel() {
    private val kick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val summaries = MutableStateFlow<Map<String, Result<Balance>>>(emptyMap())
    private var summaryJob: Job? = null

    private val accounts: StateFlow<Result<List<Account>>?> =
        merge(
            vault.secretsFlow
                .map { it.appKey }
                .filterNotNull()
                .distinctUntilChanged()
                .map { }
                .catch { }, // store 손상 등으로 죽어도 재조회 트리거가 하나 준 것뿐 — kick 은 살아있다
            kick,
        ).mapLatest { loadResult { api.accounts() } }
            .onEach { result -> result.getOrNull()?.let { loadSummaries(it) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val ui: StateFlow<AccountsUi> =
        combine(accounts, summaries, store.data.catch { }) { result, sums, prefs ->
            when {
                result == null -> {
                    AccountsUi()
                }

                result.isFailure -> {
                    AccountsUi(error = result.exceptionOrNull()?.userMessage().orEmpty())
                }

                else -> {
                    AccountsUi(
                        rows =
                            result.getOrThrow().map { acct ->
                                // 현금성 자산 지정을 포트폴리오 화면과 똑같이 적용한다 —
                                // 두 화면의 주식·현금 비율이 다르면 어느 쪽이 맞는지 알 수 없다.
                                val folded =
                                    sums[acct.no]?.map {
                                        Rebalance.foldCash(it, readCashCodes(prefs, cashKey(acct.no)))
                                    }
                                AccountRow(acct.no, prefs[nameKey(acct.no)], folded)
                            },
                    )
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUi())

    /**
     * 계좌마다 잔고를 **하나씩** 부른다. NH 는 초당 약 5건이라 한꺼번에 몰아치면 429 를 맞는다.
     * 도착하는 대로 방출하므로 화면은 첫 계좌부터 채워진다. 한 계좌가 실패해도 나머지는 그대로다.
     */
    private fun loadSummaries(list: List<Account>) {
        summaryJob?.cancel()
        summaries.value = emptyMap()
        summaryJob =
            viewModelScope.launch {
                list.forEach { acct ->
                    val result = loadResult { api.balance(acct) }
                    summaries.update { it + (acct.no to result) }
                }
            }
    }

    /** [name] 이 비어 있으면 이름을 지운다 — 그러면 계좌구분이 다시 자리를 차지한다. */
    fun setName(
        no: String,
        name: String?,
    ) {
        viewModelScope.launch {
            store.edit { prefs ->
                val trimmed = name?.trim().orEmpty()
                if (trimmed.isEmpty()) prefs.remove(nameKey(no)) else prefs[nameKey(no)] = trimmed
            }
        }
    }

    fun retry() {
        kick.tryEmit(Unit)
    }
}

@Composable
fun AccountsScreen(
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    vm: AccountsViewModel = koinViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<AccountRow?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("계좌", style = MaterialTheme.typography.titleSmall) },
                actions = { IconButton(onClick = onSettings) { SettingsIcon() } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val rows = ui.rows
            when {
                ui.error != null -> {
                    Column(
                        Modifier.fillMaxSize(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(ui.error.orEmpty())
                        TextButton(onClick = vm::retry) { Text("다시 시도") }
                    }
                }

                rows == null -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                rows.isEmpty() -> {
                    Text("연결된 계좌가 없습니다", Modifier.align(Alignment.Center))
                }

                else -> {
                    Column(Modifier.fillMaxSize()) {
                        TotalCard(rows)
                        // 계좌 하나를 카드로 세운다 — 목록이 아니라 '들어갈 곳' 이라는 사실이
                        // 형태로 드러나고, 구분선만 그을 때보다 계좌끼리 훨씬 잘 갈린다.
                        LazyColumn(
                            Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(rows, key = { it.no }) { row ->
                                AccountCard(
                                    row = row,
                                    onOpen = { onOpen(row.no) },
                                    onRename = { renaming = row },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    renaming?.let { row ->
        RenameDialog(
            current = row.name,
            onDismiss = { renaming = null },
            onSet = { name ->
                vm.setName(row.no, name)
                renaming = null
            },
        )
    }
}

/**
 * 모든 계좌의 합계. 아직 다 못 받았으면 몇 개를 셌는지 밝힌다 —
 * 일부만 더한 값을 전체인 척 보여주면 돈을 잘못 읽게 된다.
 */
@Composable
private fun TotalCard(rows: List<AccountRow>) {
    val plans = rows.mapNotNull { it.plan }
    val total = plans.sumOf { it.total }
    val pl = plans.sumOf { it.totalPl }
    val cost = total - pl
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 15.dp, bottom = 13.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text("전체 평가금액", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(total.krw(), style = MaterialTheme.typography.displaySmall)
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val rate = if (cost > 0) pl * 100.0 / cost else 0.0
            Text(
                "${if (pl > 0) "+" else ""}${pl.krw()} (${rate.pct()})",
                style = MaterialTheme.typography.titleSmall,
                color = plColor(rate),
            )
            Text(
                if (plans.size == rows.size) "${rows.size}개 계좌" else "${rows.size}개 중 ${plans.size}개 집계",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider(thickness = 8.dp, color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun AccountCard(
    row: AccountRow,
    onOpen: () -> Unit,
    onRename: () -> Unit,
) {
    val plan = row.plan
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen)
            .padding(start = 14.dp, end = 14.dp, top = 13.dp, bottom = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // 이름이 없으면 계좌구분을 흐린 글자로 — 지어낸 이름이 아니라는 걸 색으로 알린다.
            Text(
                row.name ?: "운영 계좌",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color =
                    if (row.name != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.weight(1f))
            // 이름 수정은 카드 열기와 다른 44dp 대상이라 서로 잡아먹지 않는다.
            // 대상은 왼쪽으로 넓히고 연필 자체는 오른쪽 끝선에 붙인다 — IconButton 은 아이콘을
            // 한가운데 두어서, 같은 자리에 오른쪽 정렬된 금액·손익보다 15dp 안쪽으로 들어가 보인다.
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onRename),
                contentAlignment = Alignment.CenterEnd,
            ) { PencilIcon() }
        }
        Text(
            row.no + (plan?.let { " · ${it.lines.size - 1}종목" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                plan?.total?.krw() ?: if (row.summary == null) "조회 중" else "조회 실패",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                color =
                    if (plan != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            plan?.takeIf { it.totalPl != 0L }?.let {
                Text(
                    "${if (it.totalPl > 0) "+" else ""}${it.totalPl.krw()} (${it.totalPlRate.pct()})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = plColor(it.totalPlRate),
                )
            }
        }
        plan?.let { Composition(it) }
    }
}

/**
 * 주식·현금 구성. 22dp 파이가 아니라 가른 막대다 — 색은 그대로지만 비율이 눈금 없이 읽히고,
 * 포트폴리오 화면의 비중 막대와 같은 형태라 두 화면이 한 언어를 쓴다.
 */
@Composable
private fun Composition(plan: Rebalance.Plan) {
    val c = compositionColors()
    val cash = plan.lines.last().currentAmt
    val stockFraction = if (plan.total > 0) (plan.total - cash).toFloat() / plan.total else 0f
    Column(Modifier.padding(top = 11.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(BAR_HEIGHT)
                .clip(CircleShape)
                .background(c.cash),
        ) {
            Box(Modifier.fillMaxWidth(stockFraction.coerceIn(0f, 1f)).fillMaxHeight().background(c.stock))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("주식 ${(stockFraction * 100).pct2()}", style = MaterialTheme.typography.bodySmall, color = c.stockInk)
            Text(
                "현금 ${((1 - stockFraction) * 100).pct2()}",
                style = MaterialTheme.typography.bodySmall,
                color = c.cashInk,
            )
        }
    }
}

/** 부호 없는 퍼센트 — [pct] 는 수익률용이라 항상 부호를 붙인다. */
private fun Float.pct2(): String = String.format(java.util.Locale.KOREA, "%.1f%%", this)

@Composable
private fun RenameDialog(
    current: String?,
    onDismiss: () -> Unit,
    onSet: (String?) -> Unit,
) {
    var text by remember { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("계좌 이름") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(20) },
                    label = { Text("예: 종합계좌, CMA, IRP") },
                    singleLine = true,
                )
                Text(
                    "NH 는 계좌명을 주지 않습니다. 직접 붙인 이름은 이 기기에만 저장됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSet(text) }) { Text("저장") } },
        dismissButton = {
            Row {
                if (current != null) TextButton(onClick = { onSet(null) }) { Text("이름 지우기") }
                TextButton(onClick = onDismiss) { Text("취소") }
            }
        },
    )
}
