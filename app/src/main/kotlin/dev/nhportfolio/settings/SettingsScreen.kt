package dev.nhportfolio.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.nhportfolio.lock.PinMode
import dev.nhportfolio.security.Biometric
import dev.nhportfolio.security.Vault
import dev.nhportfolio.ui.userMessage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

/** 앱키·시크릿에 허용하는 문자 범위(공백·제어문자 배제). */
private val PRINTABLE = 33..126

class SettingsViewModel(
    private val vault: Vault,
    private val biometric: Biometric,
) : ViewModel() {
    val hasKeys: StateFlow<Boolean?> =
        vault.secretsFlow
            .map { it.appKey != null }
            .catch { emit(false) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val bioEnrolled: StateFlow<Boolean> =
        biometric.enrolled
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    var error by mutableStateOf<String?>(null)
        private set

    var saved by mutableStateOf(false)
        private set

    /**
     * 값을 그대로 저장하되, 키가 바뀌었을 때만 토큰을 버린다 —
     * 같은 키를 다시 붙여넣었다고 재발급이 일어나면 NH 보안 알림이 쌓인다.
     */
    fun save(
        rawKey: String,
        rawSecret: String,
    ) {
        val appKey = rawKey.trim()
        val appSecret = rawSecret.trim()
        when {
            appKey.isEmpty() || appSecret.isEmpty() -> {
                error = "앱 키와 시크릿을 모두 입력하세요"
                return
            }

            !appKey.all { it.code in PRINTABLE } || !appSecret.all { it.code in PRINTABLE } -> {
                error = "앱 키에 공백이나 줄바꿈이 섞여 있습니다"
                return
            }
        }
        viewModelScope.launch {
            runCatching {
                vault.update { current ->
                    val unchanged = current.appKey == appKey && current.appSecret == appSecret
                    current.copy(
                        appKey = appKey,
                        appSecret = appSecret,
                        token = if (unchanged) current.token else null,
                        tokenIssuedAt = if (unchanged) current.tokenIssuedAt else 0,
                        tokenExpiresAt = if (unchanged) current.tokenExpiresAt else 0,
                    )
                }
            }.onSuccess {
                error = null
                saved = true
            }.onFailure {
                error = it.userMessage().ifEmpty { "저장하지 못했습니다" }
            }
        }
    }

    fun enrollBiometric(activity: FragmentActivity) {
        viewModelScope.launch {
            if (!biometric.enroll(activity)) error = "지문을 등록하지 못했습니다"
        }
    }

    fun disableBiometric() {
        viewModelScope.launch { biometric.disable() }
    }

    fun wipe() {
        viewModelScope.launch { vault.wipe() }
    }
}

@Composable
fun SettingsScreen(
    requestPin: (PinMode, (Boolean) -> Unit) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    vm: SettingsViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val hasKeys by vm.hasKeys.collectAsStateWithLifecycle()
    val bioEnrolled by vm.bioEnrolled.collectAsStateWithLifecycle()
    val biometricAvailable = remember { Biometric.available(context) }

    var appKey by remember { mutableStateOf("") }
    var appSecret by remember { mutableStateOf("") }
    var confirmWipe by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("설정") },
                navigationIcon = {
                    if (onBack != null) TextButton(onClick = onBack) { Text("뒤로") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 20.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("NH PLUG 앱 키", style = MaterialTheme.typography.titleMedium)
            AssistChip(
                onClick = { },
                label = { Text(if (hasKeys == true) "저장됨" else "미설정") },
            )
            Text(
                "저장된 값은 다시 보여주지 않습니다. 바꾸려면 새로 입력해 저장하세요.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = appKey,
                onValueChange = { appKey = it },
                label = { Text("appkey") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = appSecret,
                onValueChange = { appSecret = it },
                label = { Text("appsecretkey") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    vm.save(appKey, appSecret)
                    appKey = ""
                    appSecret = ""
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("저장") }

            HorizontalDivider()

            if (biometricAvailable && activity != null) {
                ListItem(
                    headlineContent = { Text("지문으로 잠금 해제") },
                    trailingContent = {
                        Switch(
                            checked = bioEnrolled,
                            onCheckedChange = { enable ->
                                if (enable) {
                                    requestPin(PinMode.Verify) { ok -> if (ok) vm.enrollBiometric(activity) }
                                } else {
                                    vm.disableBiometric()
                                }
                            },
                        )
                    },
                )
            }
            ListItem(
                headlineContent = { Text("PIN 변경") },
                trailingContent = {
                    TextButton(onClick = { requestPin(PinMode.Change) { } }) { Text("변경") }
                },
            )
            ListItem(
                headlineContent = { Text("앱 초기화") },
                supportingContent = { Text("앱 키·토큰·목표 비중·PIN 을 모두 지웁니다") },
                trailingContent = {
                    TextButton(onClick = { confirmWipe = true }) {
                        Text("초기화", color = MaterialTheme.colorScheme.error)
                    }
                },
            )
        }
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("앱을 초기화할까요?") },
            text = { Text("저장된 앱 키와 목표 비중, PIN 이 모두 지워집니다. 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmWipe = false
                    scope.launch { vm.wipe() }
                }) { Text("초기화", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("취소") } },
        )
    }
}
