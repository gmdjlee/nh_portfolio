package dev.nhportfolio

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.nhportfolio.accounts.AccountsScreen
import dev.nhportfolio.lock.LockViewModel
import dev.nhportfolio.lock.PinFlow
import dev.nhportfolio.lock.PinMode
import dev.nhportfolio.portfolio.PortfolioScreen
import dev.nhportfolio.security.Biometric
import dev.nhportfolio.security.Vault
import dev.nhportfolio.settings.SettingsScreen
import dev.nhportfolio.store.themeKey
import dev.nhportfolio.ui.NhTheme
import dev.nhportfolio.ui.ThemeMode
import dev.nhportfolio.ui.isDark
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 화면 캡처·최근 앱 썸네일 차단, 오버레이를 통한 탭재킹 차단, 서드파티 자동완성이
        // appsecret 저장을 제안하지 못하게 막기.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        window.setHideOverlayWindows(true)
        window.decorView.filterTouchesWhenObscured = true
        window.decorView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS

        setContent { NhApp(this) }
    }
}

/**
 * 저장된 테마를 앱 전체에 입힌다. 게이트([AppNav])보다 바깥이라 잠금 화면에도 같은 테마가 걸린다.
 */
@Composable
private fun NhApp(activity: ComponentActivity) {
    val store: DataStore<Preferences> = koinInject()
    // 저장값을 읽기 전에는 AUTO 로 그린다 — 저장이 없을 때의 값과 같아서 깜빡임이 없다.
    val mode by remember { store.data.map { ThemeMode.from(it[themeKey]) }.catch { emit(ThemeMode.AUTO) } }
        .collectAsStateWithLifecycle(ThemeMode.AUTO)
    val dark = mode.isDark()

    // 상태·내비게이션 바 아이콘 색은 시스템이 아니라 사용자가 고른 테마를 따라야 한다.
    // onCreate 의 enableEdgeToEdge() 기본값은 SystemBarStyle.auto 라 시스템 설정을 보므로,
    // 밝은 시스템에서 다크를 고르면 어두운 아이콘이 어두운 배경에 얹혀 보이지 않는다.
    // 스크림을 투명으로 두는 건 minSdk 31 이라 시스템이 알아서 대비를 넣어주기 때문이다.
    LaunchedEffect(dark) {
        val bars =
            if (dark) {
                SystemBarStyle.dark(Color.TRANSPARENT)
            } else {
                SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
            }
        activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
    }

    NhTheme(dark) { AppNav() }
}

/** 타입 세이프 내비게이션 경로. 잠금 화면과 "키 없음" 화면은 Route 가 아니라 게이트 단계다. */
@Serializable
sealed interface Route {
    @Serializable
    data object Accounts : Route

    @Serializable
    data class Portfolio(
        val no: String,
    ) : Route

    @Serializable
    data object Settings : Route
}

/**
 * 게이트 상태기계. `NavHost` 의 시작 목적지는 항상 [Route.Accounts] 라서
 * 잠금·회전·프로세스 복원에도 백스택이 보존된다. "키 없음" 은 Route 가 아니라
 * 게이트 단계로 [SettingsScreen] 을 단독으로 그린다.
 */
@Composable
private fun AppNav() {
    val vault: Vault = koinInject()
    val biometric: Biometric = koinInject()
    val lockVm: LockViewModel = koinViewModel()
    val scope = rememberCoroutineScope()

    var corrupt by remember { mutableStateOf(false) }
    var pinRequest by remember { mutableStateOf<Pair<PinMode, (Boolean) -> Unit>?>(null) }

    val hasPin by remember { vault.hasPin.catch { corrupt = true } }.collectAsStateWithLifecycle(null)
    val unlocked by vault.unlocked.collectAsStateWithLifecycle()

    // remember(unlocked) 만으로는 부족하다 — collectAsStateWithLifecycle 내부 produceState 의
    // mutableStateOf 는 호출 위치로 기억되어, Flow 인스턴스가 바뀌어도 리셋되지 않는다. key() 로
    // 컴포지션 그룹 자체를 새로 만들어야 unlocked 전이 시 hasKeys 가 null 로 돌아가
    // "키 없음" 화면이 잘못 잠깐 보이는 걸 막는다.
    val hasKeys =
        key(unlocked) {
            remember { vault.hasKeys.catch { corrupt = true } }
                .collectAsStateWithLifecycle(null)
                .value
        }

    // 잠금 상태로 돌아올 때마다 지문 자동 프롬프트 억제 플래그를 풀고, 설정 화면이 띄워둔 PIN
    // 확인 요청도 같이 지운다 — 안 그러면 설정에서 PIN 확인이 뜬 채로 자동 잠금을 맞고 PIN 으로
    // 다시 풀었을 때, 이미 목적을 잃은 PIN 요청이 되살아나 두 번째 PIN 화면으로 보인다. PinFlow 의
    // LaunchedEffect(mode) 는 회전에도 재실행되므로 start() 안에서 풀면 회전마다 생체 인증이
    // 다시 뜬다 — 그런데 회전은 (configChanges 를 안 걸었으므로) 액티비티를 통째로 다시 만들어서
    // 이 LaunchedEffect 도 새로 시작된다. "잠긴 채로 회전"과 "풀렸다가 다시 잠김" 을 구분하려면
    // 회전에도 살아남는 rememberSaveable 로 직전 unlocked 값을 들고 있어야 한다.
    var wasUnlocked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(unlocked) {
        if (wasUnlocked && !unlocked) {
            lockVm.promptedBiometric = false
            pinRequest = null
        }
        wasUnlocked = unlocked
    }

    val nav = rememberNavController()

    if (corrupt) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("저장된 데이터가 손상되었습니다") },
            text = { Text("앱 키와 PIN 을 다시 설정해야 합니다.") },
            confirmButton = {
                TextButton(onClick = {
                    corrupt = false
                    scope.launch { vault.wipe() }
                }) { Text("초기화") }
            },
        )
        return
    }

    when {
        // DataStore 첫 읽기 전에는 아무것도 그리지 않는다 (화면 깜빡임 방지)
        hasPin == null || (unlocked && hasKeys == null) -> {
        }

        hasPin == false -> {
            PinFlow(PinMode.Setup, biometric, onDone = { })
        }

        !unlocked -> {
            PinFlow(PinMode.Verify, biometric, onDone = { })
        }

        else -> {
            Box {
                if (hasKeys == false) {
                    SettingsScreen(
                        requestPin = { mode, callback -> pinRequest = mode to callback },
                        onBack = null,
                    )
                } else {
                    NavHost(navController = nav, startDestination = Route.Accounts) {
                        composable<Route.Accounts> {
                            AccountsScreen(
                                onOpen = { nav.navigate(Route.Portfolio(it)) },
                                onSettings = { nav.navigate(Route.Settings) },
                            )
                        }
                        composable<Route.Portfolio> { entry ->
                            PortfolioScreen(
                                acctNo = entry.toRoute<Route.Portfolio>().no,
                                onBack = { nav.popBackStack() },
                            )
                        }
                        composable<Route.Settings> {
                            SettingsScreen(
                                requestPin = { mode, callback -> pinRequest = mode to callback },
                                onBack = { nav.popBackStack() },
                            )
                        }
                    }
                }
                // 설정에서 띄우는 PIN 확인·변경도 같은 전체화면 슬롯을 쓴다 (별도 창 없음).
                pinRequest?.let { (mode, callback) ->
                    PinFlow(
                        mode,
                        biometric,
                        onDone = { ok ->
                            pinRequest = null
                            callback(ok)
                        },
                        cancellable = true,
                    )
                }
            }
        }
    }
}
