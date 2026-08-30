package dev.nhportfolio

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.nhportfolio.accounts.AccountsViewModel
import dev.nhportfolio.api.NhApi
import dev.nhportfolio.lock.LockViewModel
import dev.nhportfolio.portfolio.PortfolioViewModel
import dev.nhportfolio.security.Biometric
import dev.nhportfolio.security.Vault
import dev.nhportfolio.settings.SettingsViewModel
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

/** 백그라운드에 이만큼 머물렀다 돌아오면 잠근다. */
private const val LOCK_AFTER_MS = 60_000L

// corruptionHandler 가 없으면 파일이 한 번 깨졌을 때 모든 실행이 CorruptionException 으로 죽는다.
// 초기화와 결과가 같다(래핑된 비밀은 어차피 복구 불가) — 크래시 루프 대신 Setup 게이트로 떨어진다.
private val Context.nhStore: DataStore<Preferences> by preferencesDataStore(
    name = "nh",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

val appModule =
    module {
        single { androidContext().nhStore }
        single {
            Vault(
                store = get(),
                bootCount = {
                    Settings.Global.getInt(androidContext().contentResolver, Settings.Global.BOOT_COUNT, 0)
                },
            )
        }
        single { Biometric(get(), get()) }
        single { NhApi(get()) }

        viewModelOf(::LockViewModel)
        viewModelOf(::AccountsViewModel)
        viewModelOf(::SettingsViewModel)
        viewModel { (acctNo: String) -> PortfolioViewModel(acctNo, get(), get()) }
    }

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val koin =
            startKoin {
                androidContext(this@App)
                modules(appModule)
            }.koin
        val vault = koin.get<Vault>()

        // 타이머를 걸지 않고 "돌아왔을 때 얼마나 지났나" 로 판정한다 —
        // delay 는 딥슬립 중에 흐르지 않아서 화면을 끄고 30분 뒤 돌아와도 안 잠기는 문제가 있다.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                private var stoppedAt = 0L

                override fun onStop(owner: LifecycleOwner) {
                    stoppedAt = SystemClock.elapsedRealtime()
                }

                override fun onStart(owner: LifecycleOwner) {
                    if (stoppedAt != 0L && SystemClock.elapsedRealtime() - stoppedAt >= LOCK_AFTER_MS) {
                        vault.lock()
                    }
                }
            },
        )
    }
}
