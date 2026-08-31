package dev.nhportfolio.settings

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.nhportfolio.lock.PinMode
import dev.nhportfolio.security.Biometric
import dev.nhportfolio.security.Vault
import dev.nhportfolio.store.themeKey
import dev.nhportfolio.ui.ChevronIcon
import dev.nhportfolio.ui.ThemeMode
import dev.nhportfolio.ui.groupedColors
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
    private val store: DataStore<Preferences>,
) : ViewModel() {
    val hasKeys: StateFlow<Boolean?> =
        vault.hasKeys
            .catch { emit(false) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val themeMode: StateFlow<ThemeMode> =
        store.data
            .map { ThemeMode.from(it[themeKey]) }
            .catch { emit(ThemeMode.AUTO) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.AUTO)

    val bioEnrolled: StateFlow<Boolean?> =
        biometric.enrolled
            .catch { emit(false) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    var error by mutableStateOf<String?>(null)
        private set

    /**
     * 값을 그대로 저장하되, 키가 바뀌었을 때만 토큰을 버린다 —
     * 같은 키를 다시 붙여넣었다고 재발급이 일어나면 NH 보안 알림이 쌓인다.
     */
    fun save(
        appKey: String,
        appSecret: String,
    ) {
        val key = appKey.trim()
        val secret = appSecret.trim()
        when {
            key.isEmpty() || secret.isEmpty() -> {
                error = "앱 키와 시크릿을 모두 입력하세요"
                return
            }

            !key.all { it.code in PRINTABLE } || !secret.all { it.code in PRINTABLE } -> {
                error = "앱 키에 공백이나 줄바꿈이 섞여 있습니다"
                return
            }
        }
        viewModelScope.launch {
            runCatching {
                vault.update { current ->
                    val unchanged = current.appKey == key && current.appSecret == secret
                    current.copy(
                        appKey = key,
                        appSecret = secret,
                        token = if (unchanged) current.token else null,
                        tokenIssuedAt = if (unchanged) current.tokenIssuedAt else 0,
                        tokenExpiresAt = if (unchanged) current.tokenExpiresAt else 0,
                    )
                }
            }.onSuccess {
                error = null
            }.onFailure {
                error = it.userMessage().ifEmpty { "저장하지 못했습니다" }
            }
        }
    }

    /** 저장에 실패하면 [themeMode] 가 그대로라 버튼도 움직이지 않는다 — 오류를 같이 띄운다. */
    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch {
            runCatching { store.edit { it[themeKey] = mode.name } }
                .onSuccess { error = null }
                .onFailure { error = "화면 테마를 저장하지 못했습니다" }
        }
    }

    fun enrollBiometric(activity: FragmentActivity) {
        viewModelScope.launch {
            error = if (biometric.enroll(activity)) null else "지문을 등록하지 못했습니다"
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
    val hasKeys by vm.hasKeys.collectAsStateWithLifecycle()
    val bioEnrolled by vm.bioEnrolled.collectAsStateWithLifecycle()
    val themeMode by vm.themeMode.collectAsStateWithLifecycle()
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
        // 구분선으로만 갈라 두면 어디까지가 한 덩어리인지 알 수 없다 —
        // 앱 키 / 화면 / 보안 / 위험 넷을 묶음으로 세우고 작은 라벨로 이름을 붙인다.
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    // 바탕은 카드보다 어둡다 — 그래야 묶음이 떠 보인다(groupedColors 참고).
                    .background(groupedColors().page)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SectionLabel("NH PLUG 앱 키")
            SettingsGroup {
                Column(
                    Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    KeyStatus(hasKeys)
                    Text(
                        "저장된 값은 다시 보여주지 않습니다. 바꾸려면 새로 입력해 저장하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = appKey,
                        onValueChange = { appKey = it },
                        label = { Text("appkey") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions =
                            KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = appSecret,
                        onValueChange = { appSecret = it },
                        label = { Text("appsecretkey") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions =
                            KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    vm.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    Button(
                        onClick = {
                            vm.save(appKey, appSecret)
                            appKey = ""
                            appSecret = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("저장") }
                }
            }

            SectionLabel("화면", top = 12.dp)
            SettingsGroup {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    Text("테마", style = MaterialTheme.typography.bodyLarge)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        ThemeMode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = themeMode == mode,
                                onClick = { vm.setTheme(mode) },
                                shape = SegmentedButtonDefaults.itemShape(index, ThemeMode.entries.size),
                            ) { Text(mode.label) }
                        }
                    }
                }
            }

            SectionLabel("보안", top = 12.dp)
            SettingsGroup {
                Column {
                    if (biometricAvailable && activity != null) {
                        SettingsRow("지문으로 잠금 해제") {
                            Switch(
                                checked = bioEnrolled == true,
                                enabled = bioEnrolled != null,
                                onCheckedChange = { enable ->
                                    if (enable) {
                                        requestPin(PinMode.Verify) { ok -> if (ok) vm.enrollBiometric(activity) }
                                    } else {
                                        vm.disableBiometric()
                                    }
                                },
                            )
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 14.dp))
                    }
                    SettingsRow("PIN 변경", onClick = { requestPin(PinMode.Change) { } }) { ChevronIcon() }
                }
            }

            // 되돌릴 수 없는 조작이라 설명을 접어 두지 않는다 — 라벨과 문장을 함께 둔다.
            SettingsGroup(Modifier.padding(top = 20.dp, bottom = 20.dp)) {
                Column(
                    Modifier
                        .clickable { confirmWipe = true }
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "앱 초기화",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.weight(1f))
                        ChevronIcon(tint = MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                    }
                    Text(
                        "앱 키·토큰·목표 비중·PIN 을 모두 지웁니다. 되돌릴 수 없습니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    WipeDialog(
        confirmWipe = confirmWipe,
        onDismiss = { confirmWipe = false },
        onConfirm = {
            confirmWipe = false
            vm.wipe()
        },
    )
}

/** 묶음 이름. 본문보다 작고 자간을 벌려 제목이 아니라 꼬리표로 읽히게 한다. */
@Composable
private fun SectionLabel(
    text: String,
    top: Dp = 0.dp,
) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(start = 4.dp, top = top),
    )
}

/** 설정 한 묶음. 바탕보다 밝은 카드 한 장으로 어디까지가 한 덩어리인지 알린다. */
@Composable
private fun SettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val g = groupedColors()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = g.card,
        border = BorderStroke(1.dp, g.line),
        content = content,
    )
}

/** 묶음 안의 한 줄. [onClick] 이 없으면 오른쪽 조작(스위치 등)만 누를 수 있다. */
@Composable
private fun SettingsRow(
    label: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp)
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/** 앱 키가 저장돼 있는지. 점 하나 + 글자다 — 칩은 누를 수 있어 보이는데 실제로는 상태 표시였다. */
@Composable
private fun KeyStatus(hasKeys: Boolean?) {
    val color =
        when (hasKeys) {
            true -> MaterialTheme.colorScheme.primary
            false -> MaterialTheme.colorScheme.error
            null -> MaterialTheme.colorScheme.onSurfaceVariant
        }
    Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(
            when (hasKeys) {
                true -> "저장됨"
                false -> "미설정"
                null -> "확인 중…"
            },
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

/** 앱 초기화 확인. [confirmWipe] 가 false 면 아무것도 그리지 않는다. */
@Composable
private fun WipeDialog(
    confirmWipe: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    if (!confirmWipe) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("앱을 초기화할까요?") },
        text = { Text("저장된 앱 키와 목표 비중, PIN 이 모두 지워집니다. 되돌릴 수 없습니다.") },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("초기화", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } },
    )
}
