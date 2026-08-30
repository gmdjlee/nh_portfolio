package dev.nhportfolio

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import dev.nhportfolio.ui.NhTheme
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

        setContent { NhTheme { AppNav() } }
    }
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
    val hasKeys by remember(unlocked) {
        vault.secretsFlow.map { it.appKey != null }.catch { corrupt = true }
    }.collectAsStateWithLifecycle(null)

    // 잠금 상태로 돌아올 때마다 지문 자동 프롬프트 억제 플래그를 푼다. PinFlow 의
    // LaunchedEffect(mode) 는 회전에도 재실행되므로 start() 안에서 풀면 회전마다 생체 인증이
    // 다시 뜬다 — 그 버그를 막으려고 게이트가 잠금 전이를 관찰하는 여기서 한다.
    LaunchedEffect(unlocked) {
        if (!unlocked) lockVm.promptedBiometric = false
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
                    PinFlow(mode, biometric, onDone = { ok ->
                        pinRequest = null
                        callback(ok)
                    })
                }
            }
        }
    }
}
