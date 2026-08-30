@file:Suppress("MatchingDeclarationName")

package dev.nhportfolio.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.nhportfolio.api.NhApi
import dev.nhportfolio.api.loadResult
import dev.nhportfolio.model.Account
import dev.nhportfolio.security.Vault
import dev.nhportfolio.ui.userMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import org.koin.androidx.compose.koinViewModel

class AccountsViewModel(
    vault: Vault,
    api: NhApi,
) : ViewModel() {
    private val kick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** null = 아직 로딩 중. 앱 키가 바뀌면 자동으로 다시 조회한다. */
    val ui: StateFlow<Result<List<Account>>?> =
        merge(
            vault.secretsFlow
                .map { it.appKey }
                .filterNotNull()
                .distinctUntilChanged()
                .map { },
            kick,
        ).mapLatest { loadResult { api.accounts() } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun retry() {
        kick.tryEmit(Unit)
    }
}

@Composable
fun AccountsScreen(
    onOpen: (acctNo: String) -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    vm: AccountsViewModel = koinViewModel(),
) {
    val state by vm.ui.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("계좌") },
                actions = { TextButton(onClick = onSettings) { Text("설정") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            val result = state
            when {
                result == null -> {
                    CircularProgressIndicator()
                }

                result.isFailure -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(result.exceptionOrNull()!!.userMessage())
                        TextButton(onClick = vm::retry) { Text("다시 시도") }
                    }
                }

                result.getOrThrow().isEmpty() -> {
                    Text("연결된 계좌가 없습니다")
                }

                else -> {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(result.getOrThrow(), key = { it.no }) { account ->
                            Card(
                                modifier =
                                    Modifier
                                        .fillMaxSize()
                                        .padding(horizontal = 16.dp, vertical = 6.dp)
                                        .clickable { onOpen(account.no) },
                            ) {
                                Text(
                                    text = account.no,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(20.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
