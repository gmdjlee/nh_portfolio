package dev.nhportfolio.lock

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.nhportfolio.security.Biometric
import dev.nhportfolio.security.PinResult
import dev.nhportfolio.security.Vault
import dev.nhportfolio.security.weakPin
import dev.nhportfolio.ui.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

const val PIN_LENGTH = 6

enum class PinMode {
    /** 최초 PIN 설정 — 입력 후 확인. */
    Setup,

    /** 잠금 해제 — 한 번 입력. */
    Verify,

    /** PIN 변경 — 기존 PIN 확인 후 새 PIN 입력·확인. */
    Change,
}

/**
 * PIN 입력 상태를 들고 있는다. 입력한 숫자는 [CharArray] 로만 다루고
 * `rememberSaveable`·`SavedStateHandle` 을 쓰지 않는다 — PIN 이 시스템 saved-state 번들에
 * 직렬화되면 안 되기 때문이다(설정 중 프로세스가 죽으면 설정을 처음부터 다시 한다).
 */
class LockViewModel(
    private val vault: Vault,
) : ViewModel() {
    private enum class Phase { VerifyOld, Enter, Confirm }

    private val buffer = CharArray(PIN_LENGTH)
    private var firstEntry: CharArray? = null
    private var phase = Phase.Enter
    private var mode = PinMode.Setup
    private var countdown: Job? = null
    private val completedChannel = Channel<Boolean>(Channel.BUFFERED)

    /** 지문 자동 프롬프트를 화면당 한 번만 띄우기 위한 플래그. 회전에도 살아남는다. */
    var promptedBiometric = false

    val completed: Flow<Boolean> = completedChannel.receiveAsFlow()

    var length by mutableIntStateOf(0)
        private set

    var title by mutableStateOf("")
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var lockedSeconds by mutableIntStateOf(0)
        private set

    var busy by mutableStateOf(false)
        private set

    fun start(newMode: PinMode) {
        mode = newMode
        phase = if (newMode == PinMode.Setup) Phase.Enter else Phase.VerifyOld
        clearEntry()
        error = null
        title =
            when (newMode) {
                PinMode.Setup -> "사용할 PIN 6자리를 입력하세요"
                PinMode.Verify -> "PIN 을 입력하세요"
                PinMode.Change -> "현재 PIN 을 입력하세요"
            }
    }

    fun press(digit: Char) {
        if (busy || lockedSeconds > 0 || length >= PIN_LENGTH) return
        buffer[length++] = digit
        error = null
        if (length == PIN_LENGTH) submit()
    }

    fun backspace() {
        if (busy || length == 0) return
        buffer[--length] = '0'
    }

    fun cancel() {
        clearEntry()
        completedChannel.trySend(false)
    }

    fun onBiometricResult(success: Boolean) {
        if (success) completedChannel.trySend(true)
    }

    override fun onCleared() {
        clearEntry()
        countdown?.cancel()
    }

    private fun submit() {
        val entered = buffer.copyOf(length)
        buffer.fill('0')
        length = 0
        viewModelScope.launch {
            busy = true
            try {
                when (phase) {
                    Phase.VerifyOld -> verify(entered)
                    Phase.Enter -> enterNew(entered)
                    Phase.Confirm -> confirmNew(entered)
                }
            } finally {
                busy = false
            }
        }
    }

    private suspend fun verify(entered: CharArray) {
        val result =
            runCatching { vault.unlockWithPin(entered) }.getOrElse {
                error = it.userMessage().ifEmpty { "잠금 해제에 실패했습니다" }
                return
            }
        when (result) {
            is PinResult.Ok -> {
                if (mode == PinMode.Verify) {
                    completedChannel.trySend(true)
                } else {
                    phase = Phase.Enter
                    title = "새 PIN 6자리를 입력하세요"
                }
            }

            is PinResult.Wrong -> {
                error = "PIN 이 맞지 않습니다 (남은 시도 ${result.remaining}회)"
            }

            is PinResult.LockedFor -> {
                startCountdown(result.millis)
            }
        }
    }

    private fun enterNew(entered: CharArray) {
        if (weakPin(entered)) {
            entered.fill('0')
            error = "연속되거나 같은 숫자는 쓸 수 없습니다"
            return
        }
        firstEntry?.fill('0')
        firstEntry = entered
        phase = Phase.Confirm
        title = "PIN 을 한 번 더 입력하세요"
    }

    private suspend fun confirmNew(entered: CharArray) {
        val first = firstEntry
        if (first == null || !first.contentEquals(entered)) {
            entered.fill('0')
            first?.fill('0')
            firstEntry = null
            phase = Phase.Enter
            title = "새 PIN 6자리를 입력하세요"
            error = "PIN 이 일치하지 않습니다"
            return
        }
        entered.fill('0')
        runCatching { vault.setPin(first) } // setPin 이 first 를 지운다
            .onSuccess { completedChannel.trySend(true) }
            .onFailure { error = it.userMessage().ifEmpty { "PIN 설정에 실패했습니다" } }
        firstEntry = null
    }

    private fun startCountdown(millis: Long) {
        countdown?.cancel()
        countdown =
            viewModelScope.launch {
                var left = (millis / 1_000).toInt() + 1
                while (left > 0) {
                    lockedSeconds = left
                    delay(1_000)
                    left--
                }
                lockedSeconds = 0
            }
    }

    private fun clearEntry() {
        buffer.fill('0')
        firstEntry?.fill('0')
        firstEntry = null
        length = 0
    }
}

/**
 * 잠금 해제·PIN 설정·PIN 변경에 모두 쓰는 전체 화면. NavHost 밖 오버레이로 띄우므로
 * Back 으로 빠져나갈 수 없다([BackHandler]).
 */
@Composable
fun PinFlow(
    mode: PinMode,
    biometric: Biometric,
    onDone: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    vm: LockViewModel = koinViewModel(),
) {
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val enrolled by biometric.enrolled.collectAsStateWithLifecycle(false)
    val canUseBiometric = mode == PinMode.Verify && enrolled && activity != null

    LaunchedEffect(mode) { vm.start(mode) }
    val currentOnDone by rememberUpdatedState(onDone)
    LaunchedEffect(Unit) { vm.completed.collect { currentOnDone(it) } }
    // BiometricPrompt 는 컴포저블 스코프에서 띄운다 — viewModelScope 에서 기다리면
    // 회전으로 액티비티가 죽었을 때 영원히 돌아오지 않는다.
    LaunchedEffect(canUseBiometric) {
        if (canUseBiometric && !vm.promptedBiometric) {
            vm.promptedBiometric = true
            vm.onBiometricResult(biometric.unlock(activity))
        }
    }
    if (mode == PinMode.Verify) BackHandler { /* 잠긴 동안 뒤로가기로 화면을 벗어날 수 없다 */ }

    Surface(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(vm.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.size(24.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(PIN_LENGTH) { index ->
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(
                                if (index < vm.length) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                },
                            ),
                    )
                }
            }

            Spacer(Modifier.size(16.dp))
            Text(
                text =
                    when {
                        vm.lockedSeconds > 0 -> "${vm.lockedSeconds}초 후에 다시 시도할 수 있습니다"
                        else -> vm.error.orEmpty()
                    },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.size(24.dp))
            PinPad(
                enabled = !vm.busy && vm.lockedSeconds == 0,
                onDigit = vm::press,
                onBackspace = vm::backspace,
            )

            if (canUseBiometric) {
                TextButton(onClick = {
                    scope.launch { vm.onBiometricResult(biometric.unlock(activity)) }
                }) {
                    Text("지문으로 잠금 해제")
                }
            }
            if (mode == PinMode.Change) {
                TextButton(onClick = vm::cancel) { Text("취소") }
            }
        }
    }
}

@Composable
private fun PinPad(
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf("123", "456", "789", " 0<").forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { key ->
                    when (key) {
                        ' ' -> Spacer(Modifier.size(72.dp))
                        '<' -> PinKey("⌫", enabled) { onBackspace() }
                        else -> PinKey(key.toString(), enabled) { onDigit(key) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PinKey(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(72.dp),
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}
