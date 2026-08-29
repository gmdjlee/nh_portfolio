# NH Portfolio — 설계 사양 (2026-08-30)

> **상태**: 사용자 검토 대기. 2라운드 적대적 리뷰(보안/플랫폼·NH API·과잉설계·완전성) 반영판(개정 3)에 아래 확정 결정을 적용했다.
>
> **확정 결정(2026-08-29~30, 사용자 선택)**
> - 접근 방식 **A. 단일 모듈 미니멀** · Kotlin only · Compose · Ktor(REST+WS) + kotlinx.serialization + Koin
> - 자산 범위: 국내주식 v1. 해외주식 seam(§9)·포그라운드 서비스 seam(§8)은 설계만, 구현 안 함
> - **minSdk 31** · 예수금 = **D+2 예수금(`nxt2_dd_dca`)** · 백그라운드 **60 s 경과 후 복귀 시 잠금** · 401 재발급 억제 창 **1 h** · PIN 5회 실패부터 30 s×2ⁿ(상한 1 h), 자동 초기화 없음
> - **모의투자(acct_type 03) 계좌 숨김** → 운영 호스트(`api.nhplug.com`)만 사용. 모의 재도입 = `NhEnv` enum + `accounts()` 필터 1줄
> - 자격증명: 설정 화면 입력 + Keystore 봉인(write-only) · 6자리 PIN + 지문(Class 3, CryptoObject) · organic M3 테마(자연 팔레트)
> - 품질: JVM 단위 테스트 · detekt + ktlint · GitHub Actions · 로컬 git, 구현 단계별 커밋(원격 푸시 없음)
> - 리뷰가 제안했으나 채택하지 않은 것: 10회 실패 자동 wipe(자기 DoS), 루트 탐지(우회 가능·이득 없음), Repository/AssetClass 계층(구현 1개)


개정 2 리뷰의 blocker 3 · major 17 · minor 전부를 반영했다. 구조 변경은 넷: **(a)** 잠금이 타이머가 아니라 "복귀 시 경과 시간" 판정, **(b)** 잠기면 `secretsFlow`가 빈 `Secrets`를 방출해 소켓이 구조적으로 취소됨(타이밍 불변식 삭제), **(c)** "키 없음"은 Route가 아니라 게이트 단계(startDestination 불변 → 백스택 보존), **(d)** 예수금 = `nxt2_dd_dca`(D+2). 그 외는 전부 1–3줄 수정이다. minSdk 31(설정 가능한 유일한 API 31 의존: `setHideOverlayWindows`).

## 1 아키텍처 개요

- 단일 Gradle 모듈 `:app`, package-by-feature. 경계 셋:
  - `api/NhApi.kt` — HTTP/WS/NH JSON을 아는 **유일한 파일**. DTO는 `private`(컴파일러가 경계 강제; 외부에 노출되는 타입은 `model/`뿐).
  - `security/` — Keystore/DataStore 비밀을 만지는 유일한 코드.
  - feature 패키지(`lock/ accounts/ portfolio/ settings/`) — Screen + ViewModel. NH 필드명을 모른다.
  - `model/` + `portfolio/Rebalance.kt` — 순수 Kotlin. detekt `ForbiddenImport` 1개(§12)로 강제.
- 계층 없음: Repository·UseCase·TokenStore·AppLock·RealtimeFeed 클래스 없음. DataStore가 저장소, `Vault`의 봉인 blob이 유일한 토큰 저장소(메모리 홀더 없음), `stateIn(WhileSubscribed)`가 라이프사이클, DEK 유무가 잠금 상태.
- 남긴 추상화와 근거:
  | 추상화 | 이름 붙은 다음 요구 |
  |---|---|
  | `NhResponse<A, B>` + `expect(block, empty)` | NH 전 엔드포인트 동일 봉투. gbstock 잔고 = private DTO 2개 + 함수 1개 |
  | `NhApi.CHANNELS = listOf("d2")` | 해외 체결통보 `d0`는 같은 7070 세션. 리스트 1줄 추가가 seam |
  | `Vault(hmac, elapsed, bootCount)` 생성자 훅 | 인터페이스 없이 JVM 테스트(SecretKeySpec HMAC, 가짜 시계) |
  | `NhApi(vault, engine)` 생성자 훅 | MockEngine 테스트 |
  | 인터페이스 0, 팩토리 0, 설정 클래스 0 | |

## 2 모듈 & 패키지 레이아웃

```
root/  settings.gradle.kts  build.gradle.kts  gradle.properties  gradle/libs.versions.toml  gradlew(.bat) + gradle/wrapper/*
       config/detekt.yml  .editorconfig  .github/workflows/ci.yml  .gitignore  README.md
app/build.gradle.kts  app/proguard-rules.pro
app/src/main/AndroidManifest.xml       INTERNET, HIDE_OVERLAY_WINDOWS; allowBackup=false, dataExtractionRules; FragmentActivity 1개
app/src/main/res/xml/data_extraction_rules.xml   cloud-backup + device-transfer 전부 exclude
app/src/main/res/values/strings.xml, res/mipmap*/ic_launcher (adaptive 3)
app/src/main/kotlin/dev/nhportfolio/
  App.kt                 Application: private Context.nhStore(corruptionHandler) + appModule + startKoin; ProcessLifecycleOwner: onStop 시각 기록, onStart에 60 s 경과면 vault.lock()
  MainActivity.kt        FragmentActivity: FLAG_SECURE, filterTouchesWhenObscured, setHideOverlayWindows, importantForAutofill=NO_EXCLUDE_DESCENDANTS; @Serializable Route; AppNav() 게이트 + pinRequest 오버레이 호스트
  model/Model.kt         Account, Holding, Balance, Fill — 시장 무관, KRW Long
  api/NhApi.kt           REST/WS/TOKEN_URL 상수, LIVE_TYPES, NhJson, NhResponse, NhException, expect, loadResult, class NhApi(token/call/pages/accounts/balance/fills), private DTO
  security/Vault.kt      object K(타입 키), 순수 함수(seal/open/pbkdf2/lockoutMillis/weakPin), Secrets, PinResult, VaultCorruptException, Vault, private keystoreHmac
  security/Biometric.kt  인증-게이트 AES 키 + BiometricPrompt(CryptoObject) 등록/해제 + available() — 파일 1개 삭제로 기능 제거
  lock/LockScreen.kt     PinMode, LockViewModel, PinFlow(게이트·설정 공용), PinPad
  accounts/AccountsScreen.kt   AccountsViewModel + AccountsScreen
  portfolio/Rebalance.kt       object Rebalance (순수)
  portfolio/PortfolioScreen.kt PortfolioUi + PortfolioViewModel + PortfolioScreen + private targetsKey()
  settings/SettingsScreen.kt   SettingsViewModel + SettingsScreen
  ui/Theme.kt            organic M3, NhTheme()
  ui/Format.kt           Long.krw(), Int.bpPct(), Double.pct(), Long.shares(), plColor(), Throwable.userMessage()
app/src/test/kotlin/dev/nhportfolio/
  RebalanceTest  NhApiTest(MockEngine)  NhSocketTest(embedded CIO WS 서버)  CryptoTest  VaultTest  FormatTest
```
파일 수 ≈ 40 (main 13 + test 6 + 설정/리소스/wrapper/아이콘 ≈ 21). `di/ nav/ security/Prefs.kt security/Crypto.kt` 없음(소비자 1개씩 → 소비자 파일로). 화면 하나 = 파일 1개 + `viewModelOf` 1줄 + Route 선언 1줄 + `composable<>` 항목 1개.

## 3 핵심 타입 (Kotlin 시그니처)

```kotlin
// model/Model.kt — 순수. 금액 KRW Long(API int64). 수량 Long(krstock: itg_bnc_qty double→Long, gbstock: int64).
data class Account(val no: String)                                // acct_no(11자리 숫자, spec 예시 "20101036881"); 운영(01·02) 계좌만 모델에 도달
data class Holding(
    val code: String, val name: String,          // iem_cd, iem_nm
    val qty: Long, val remainQty: Long,          // 보유수량 = itg_bnc_qty, 잔고수량 = rsdl_qty
    val avgPrice: Long, val price: Long,         // 평균매입가 = phs_pr, 현재가 = now_pr
    val evalAmt: Long, val pnlRate: Double,      // 평가금액 = eal_amt, 수익률 = pft_rt(단위 §14)
)
data class Balance(val cash: Long /* Output_0.nxt2_dd_dca — D+2 예수금: 당일 체결 즉시 반영 */, val holdings: List<Holding>)
data class Fill(val acctNo: String, val name: String, val qty: Long, val price: Long, val time: String /* conctime HHmmss: 스낵바 표시 + 동일 체결 구분 */)

// portfolio/Rebalance.kt — 순수. 가중치 basis point Int(1250 = 12.50%).
object Rebalance {
    const val CASH = "\$CASH"                    // 종목코드가 될 수 없는 값(NASDAQ에 "CASH" 티커 존재 — gbstock 대비)
    data class Line(val code: String, val currentAmt: Long, val weightBp: Int, val targetBp: Int?, val deltaShares: Long?) // null = 목표 없음 | price≤0
    data class Plan(val lines: List<Line> /* 마지막 = CASH */, val total: Long, val cashAfter: Long, val targetSumBp: Int)
    fun plan(b: Balance, targetsBp: Map<String, Int>): Plan     // require(targetsBp.values.all { it in 0..10_000 })
}

// api/NhApi.kt — 전부 한 파일
private const val REST = "https://api.nhplug.com:8443"                          // 운영 전용(확정: 모의계좌 숨김). 모의 재도입 = NhEnv enum(moapi.nhplug.com:8443 / :17070) + accounts() 필터
private const val WS = "wss://api.nhplug.com:7070/websocket"                   // 국내 시세 + 전 통보 채널; 경로 /websocket 필수
private const val TOKEN_URL = "$REST/oauth2/token"
private val LIVE_TYPES = setOf("01", "02")                                      // acctinfo acct_type: 01 운영 일반, 02 운영 주문대리인. 그 외(03 모의 등)는 목록에서 제외
val NhJson = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }
@Serializable data class NhResponse<A, B>(
    @SerialName("rsp_cd") val rspCd: String = "", @SerialName("rsp_msg") val rspMsg: String = "",
    @SerialName("Output_0") val output0: A? = null, @SerialName("Output_1") val output1: B? = null,
)
class NhException(val code: String, message: String) : Exception(message)   // code: rsp_cd | "HTTP<status>" | "AUTH" | "WS"
private val OK_CODES = setOf("00000", "00166", "00221", "13578")
val NhResponse<*, *>.ok: Boolean get() = rspCd in OK_CODES || "완료" in rspMsg
fun <T> NhResponse<*, *>.expect(block: T?, empty: T): T = block ?: if (ok) empty else throw NhException(rspCd, rspMsg)
inline fun <T> loadResult(block: () -> T): Result<T> =                       // 앱 전체 유일한 예외 래핑 관용구
    try { Result.success(block()) } catch (e: CancellationException) { throw e } catch (e: Exception) { Result.failure(e) }

class NhApi(
    private val vault: Vault,
    engine: HttpClientEngine = OkHttp.create { config { pingInterval(30, TimeUnit.SECONDS); retryOnConnectionFailure(false) } }, // OkHttp 자체 재전송 금지(토큰 POST 이중 발급 경로 차단)
) {
    private val client = HttpClient(engine) {
        expectSuccess = false                                                // HTTP 상태는 진실이 아님; 판정은 call/pages
        install(WebSockets)
        install(HttpTimeout) { requestTimeoutMillis = 15_000; connectTimeoutMillis = 10_000 }
    }
    private val tokenMutex = Mutex()
    suspend fun accounts(): List<Account>            // /n2/acctinfo {"Input_0":{}}, 전 페이지 합산, acct_type ∈ LIVE_TYPES만
    suspend fun balance(acct: Account): Balance      // /krstock/inquiry/v1/balance
    fun fills(): Flow<Fill> = fillsFrom(WS)
    internal fun fillsFrom(ws: String): Flow<Fill>   // 테스트가 embedded 서버 URL 주입; 세션 flow는 이 함수 안 인라인(§8)
    private suspend fun token(rejected: String? = null): String
    private suspend fun issueToken(appKey: String, appSecret: String): TokenRes
    private suspend fun call(path: String, body: JsonObject, cts: String?): HttpResponse
    private suspend inline fun <reified A, reified B> pages(path: String, input: JsonObject): List<NhResponse<A, B>>
    companion object {
        internal val CHANNELS = listOf("d2")                                  // 해외 체결통보 "d0" 여기 1줄
        /** 정확히 {"header":{"token":"<token>","tr_type":"1"},"body":{"tr_cd":"<trCd>","tr_key":""}} — tr_key는 빈 문자열로 반드시 존재, tr_type은 문자열 "1". */
        internal fun subscribeFrame(token: String, trCd: String): String     // buildJsonObject, 문자열 보간 없음
        internal fun parseFill(text: String): Fill?                          // runCatching { header.tr_cd ∈ CHANNELS && body → FillDto.toFill() }.getOrNull()
        internal fun backoffMs(streak: Int): Long = minOf(30_000L, 1_000L shl minOf(streak, 5))
    }
}
// 파일 하단, 전부 private
@Serializable private data class AccountDto(@SerialName("acct_no") val no: String, @SerialName("acct_type") val type: String)
@Serializable private data class BalanceSummaryDto(@SerialName("nxt2_dd_dca") val cash: Long = 0)   // dca(D+0)는 모델에 두지 않음(§6)
@Serializable private data class HoldingDto(
    @SerialName("iem_cd") val code: String, @SerialName("iem_nm") val name: String = "",
    @SerialName("itg_bnc_qty") val qty: Double = 0.0, @SerialName("rsdl_qty") val remainQty: Double = 0.0,
    @SerialName("phs_pr") val avgPrice: Long = 0, @SerialName("now_pr") val price: Long = 0,
    @SerialName("eal_amt") val evalAmt: Long = 0, @SerialName("pft_rt") val pnlRate: Double = 0.0,
) { fun toHolding() = Holding(code, name, qty.toLong(), remainQty.toLong(), avgPrice, price, evalAmt, pnlRate) }
@Serializable private data class FillDto(                                     // 실제 d2가 반드시 갖는 3개는 기본값 없음 → ack/오류 프레임은 디코드 실패 = null
    val accountno: String, val concgty: String, val concprc: String,
    @SerialName("issue_nm") val name: String = "", val conctime: String = "",
) {
    fun toFill(): Fill? = Fill(accountno, name, concgty.trim().toLongOrNull() ?: return null,   // "0000000005" → 5; 비숫자 → null(연결 유지)
        concprc.trim().toLongOrNull() ?: return null, conctime)
}
@Serializable private data class TokenRes(@SerialName("access_token") val accessToken: String, @SerialName("expires_in") val expiresIn: Long = 0) {
    override fun toString() = "TokenRes(***)"
}

// security/Vault.kt — 한 파일: 키, 순수 함수, Vault
object K { DEK_PIN, DEK_BIO, SECRETS, SALT, PBKDF2_ITERS, FAILS, LOCK_ELAPSED, LOCK_BOOT }   // 타입 Preferences 키
const val PBKDF2_ITERS = 10_000                                   // 보안 상한 아님(§7) — 형식적 stretching; setPin 시 K.PBKDF2_ITERS에 저장, unlock은 저장값 사용
fun seal(key: ByteArray, plain: ByteArray): ByteArray            // AES/GCM/NoPadding, iv(12) || ct, tag 128
fun open(key: ByteArray, blob: ByteArray): ByteArray             // 틀린 키/변조 → AEADBadTagException; 잘린 blob → 그 외 예외
fun pbkdf2(pin: CharArray, salt: ByteArray, iterations: Int): ByteArray   // PBEKeySpec … finally spec.clearPassword(); 32B
fun lockoutMillis(fails: Int): Long = if (fails < 5) 0 else (30_000L shl minOf(fails - 5, 7)).coerceAtMost(3_600_000L)   // shift 포화: Long.shl은 하위 6비트만 써서 54회부터 음수였음
fun weakPin(pin: CharArray): Boolean {                           // 000000 / 123456 / 654321 류
    val d = pin.map { it - '0' }.zipWithNext { a, b -> b - a }.toSet()
    return d.size == 1 && d.first() in -1..1
}
@Serializable data class Secrets(val appKey: String? = null, val appSecret: String? = null,
    val token: String? = null, val tokenIssuedAt: Long = 0, val tokenExpiresAt: Long = 0) { override fun toString() = "Secrets(***)" }
sealed interface PinResult { data object Ok : PinResult; data class Wrong(val remaining: Int) : PinResult; data class LockedFor(val millis: Long) : PinResult }
class VaultCorruptException : IllegalStateException("secrets corrupt")   // cause 미첨부: 평문/JSON이 스택트레이스에 못 실림
class Vault(
    private val store: DataStore<Preferences>,
    private val hmac: (data: ByteArray, create: Boolean) -> ByteArray? = ::keystoreHmac,   // create=false에 키 없음 → null; 테스트: SecretKeySpec HMAC(create 무시)
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,        // 단조 시계. 벽시계 미사용
    private val bootCount: () -> Int,                                      // Koin: Settings.Global.BOOT_COUNT; 테스트: 가짜
) {
    val unlocked: StateFlow<Boolean>            // == dek != null
    val hasPin: Flow<Boolean>                   // store.data.map { K.DEK_PIN in it }
    val secretsFlow: Flow<Secrets>              // unlocked.flatMapLatest { if (it) store.data.map(::decode) else flowOf(Secrets()) } — 잠기면 빈 Secrets(token null) 방출 → 소켓 구조적 취소
    suspend fun secrets(): Secrets              // decode(store.data.first()); 잠김 → IllegalStateException(fail fast)
    suspend fun update(f: (Secrets) -> Secrets)
    suspend fun setPin(pin: CharArray)          // weakPin → IllegalArgumentException; K.DEK_PIN 부재(최초·wipe 후) → 새 DEK·salt·nh_pin_mac 생성; 존재 → check(dek != null) 후 재래핑. Dispatchers.Default
    suspend fun unlockWithPin(pin: CharArray): PinResult   // pinMutex 직렬화(더블탭 이중 증가 방지)
    fun lock()                                  // val d = dek; dek = null; d?.fill(0)
    suspend fun wipe()                          // lock(); store.edit { it.clear() } — Keystore 키는 안 지움(blob 없는 키는 무용; setPin/enroll의 generateKey가 덮어씀) → alias 지식 0, JVM 테스트 훅 불필요
    internal fun dek(): ByteArray               // Biometric.enroll 전용
    internal fun unlockWith(dek: ByteArray)     // Biometric.unlock 전용
    @Volatile private var dek: ByteArray? = null
}
private fun keystoreHmac(data: ByteArray, create: Boolean): ByteArray?   // alias "nh_pin_mac", HMAC-SHA256, StrongBox→TEE 폴백, setUnlockedDeviceRequired(true); create → generateKey(덮어씀), else getKey ?: null

// security/Biometric.kt
class Biometric(private val store: DataStore<Preferences>, private val vault: Vault) {
    val enrolled: Flow<Boolean>
    suspend fun enroll(activity: FragmentActivity): Boolean   // unlocked 필수(UI는 PIN 재검증 후); runCatching { generateKey } 실패 → false; ENCRYPT cipher로 vault.dek() 봉인 → K.DEK_BIO
    suspend fun unlock(activity: FragmentActivity): Boolean   // DECRYPT cipher(iv from blob) → doFinal = DEK → vault.unlockWith
    suspend fun disable()
    companion object { fun available(ctx: Context) = BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG) == BIOMETRIC_SUCCESS }  // false면 제안·토글 자체를 안 보임
}

// portfolio/PortfolioScreen.kt — flat UI 타입(마지막 정상 Plan 유지 + 에러 배너 가능)
data class PortfolioUi(val balance: Balance? = null, val plan: Rebalance.Plan? = null, val lastFill: Fill? = null, val error: String? = null)
// loading == balance == null && error == null
class PortfolioViewModel(handle: SavedStateHandle, api: NhApi, store: DataStore<Preferences>) : ViewModel() {
    val ui: StateFlow<PortfolioUi>; fun refresh(); fun setTarget(code: String, bp: Int?)   // require(bp == null || bp in 0..10_000)
}
private fun targetsKey(acctNo: String) = stringPreferencesKey("targets_" + sha256(acctNo).toHexString().take(16))   // 계좌번호 평문을 디스크에 안 씀

// App.kt
private val Context.nhStore by preferencesDataStore("nh", corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() })  // 손상 = wipe와 동치(Setup 게이트), 크래시 루프 아님
val appModule = module {
    single { androidContext().nhStore }
    single { Vault(get(), bootCount = { Settings.Global.getInt(androidContext().contentResolver, Settings.Global.BOOT_COUNT, 0) }) }
    single { Biometric(get(), get()) }; single { NhApi(get()) }
    viewModelOf(::LockViewModel); viewModelOf(::AccountsViewModel); viewModelOf(::PortfolioViewModel); viewModelOf(::SettingsViewModel)
}
```

## 4 데이터 흐름

**게이트(최초 실행 포함, 명시적 상태기계)** — `MainActivity.AppNav()`. "키 없음"은 Route가 아니라 게이트 단계: `NavHost`의 start는 항상 `Accounts`(불변) → 잠금·회전·프로세스 복원에도 백스택 보존, 그래프 재설정 없음.
```kotlin
val hasPin by vault.hasPin.collectAsStateWithLifecycle(null); val unlocked by vault.unlocked.collectAsStateWithLifecycle()
val hasKeys by remember(unlocked) { vault.secretsFlow.map { it.appKey != null } }.collectAsStateWithLifecycle(null)  // 잠금 전환마다 null로 리셋(stale true/false 없음)
val nav = rememberNavController()                       // 게이트 위에서 remember → 잠금 해제 시 원래 화면 복귀
var pinRequest by remember { mutableStateOf<Pair<PinMode, (Boolean) -> Unit>?>(null) }   // 설정의 PIN 변경/지문 등록도 같은 호스트
when {
    hasPin == null || (unlocked && hasKeys == null) -> Unit        // 첫 읽기 전 빈 화면(깜빡임 방지)
    hasPin == false -> PinFlow(PinMode.Setup)                       // 6자리 → 확인 → (Biometric.available일 때만) 지문 등록 제안 → unlocked
    !unlocked -> PinFlow(PinMode.Verify)                            // NavHost 밖 오버레이 + BackHandler {}: Back이 뒤의 NavController를 pop하지 못함
    else -> Box {
        if (hasKeys == false) SettingsScreen(requestPin) else NavHost(nav, start = Route.Accounts) { … }   // 키 없음: NavHost 없음, Back = finish
        pinRequest?.let { (m, cb) -> PinFlow(m) { ok -> pinRequest = null; cb(ok) } }   // 전 PinFlow가 액티비티 창의 같은 전체화면 슬롯(ModalBottomSheet 없음)
    }
}
```
키 저장 성공 → `secretsFlow` 재방출 → `hasKeys = true` → `NavHost`가 `Accounts`로 구성(명시적 navigate/popUpTo 없음). 키 없는 상태에선 REST/WS가 호출될 화면이 없으므로 `/oauth2/token`이 발생하지 않는다.

**토큰 → 계좌** (`AccountsViewModel`):
```kotlin
private val kick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
val ui = merge(vault.secretsFlow.map { it.appKey }.filterNotNull().distinctUntilChanged().map { }, kick)   // 키 변경 시 자동 재조회
    .mapLatest { loadResult { api.accounts() } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
fun retry() = kick.tryEmit(Unit)
```
`api.accounts()` = `pages<List<AccountDto>, JsonElement>("/n2/acctinfo", {"Input_0":{}})` → `p.first().expect(p.first().output0, emptyList()) + p.drop(1).flatMap { it.output0.orEmpty() }` → `.filter { it.type in LIVE_TYPES }.map { Account(it.no) }`(모의 03·미지 코드는 목록에서 제외 — 확정 결정). 빈 목록 → "연결된 계좌가 없습니다". 탭 → `Route.Portfolio(no)`.

**잔고 → UI** (`PortfolioViewModel`):
```kotlin
private val acct = Account(handle.toRoute<Route.Portfolio>().no)
private val kick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
private val lastFill = MutableStateFlow<Fill?>(null)
private val fills = api.fills().onEach { f ->                     // 스낵바만 계좌 매칭(숫자만 비교); 재조회는 모든 d2
    if (f.acctNo.filter(Char::isDigit) == acct.no.filter(Char::isDigit)) lastFill.value = f   // Fill.time 덕에 동일 종목·수량·가격 연속 체결도 재방출
}
private val loads = merge(flowOf(Unit), kick, fills.map { }.debounce(300))   // 분할 체결 burst = 재조회 1회
    .mapLatest { loadResult { api.balance(acct) } }                            // 진행 중 fetch 취소
    .runningFold(null as Balance? to null as String?) { (last, _), r -> r.fold({ it to null }, { last to it.userMessage() }) }
    .drop(1)
val ui = combine(loads, store.data.map { targetsKey(acct.no).read(it) }, lastFill) { (b, err), t, f ->
    PortfolioUi(b, b?.let { Rebalance.plan(it, t) }, f, err)
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PortfolioUi())
```
`api.balance`: `pages<BalanceSummaryDto, List<HoldingDto>>("/krstock/inquiry/v1/balance", Input_0{act_no = acct.no, bnc_bse_cd = "1", ltg_aot_dit_cd = "1", aet_bse = "1", qut_dit_cd = "UNT"})` → `cash = first.expect(first.output0, BalanceSummaryDto()).cash`(= `nxt2_dd_dca`), `holdings = pages.flatMap { it.output1.orEmpty() }.map(HoldingDto::toHolding)`. 상수 4개는 요청 빌더에 하드코딩. 화면 이탈/백그라운드 5 s 후 체인 전체(소켓 포함) 취소; 복귀 시 `flowOf(Unit)` 재조회로 놓친 체결 반영.

**d2 → 재조회**: 사용자 범위의 **모든** d2가 재조회 트리거(토큰에 묶인 채널, 300 ms debounce 1회). ack/오류 프레임은 필수 필드 부재로 `null` → 재조회 트리거 아님(재연결마다 여분 `/balance` 없음). 스낵바 "삼성전자 5주 체결 @35,550 (11:56)".

**목표 비중 → 리밸런스**: 행 탭 → 다이얼로그(0–100.00 클램프, 비숫자 거부) → `setTarget(code, bp)`(`require(bp in 0..10_000)`) → `store.edit { targetsKey(acct.no) = Json(Map<String, Int>) }` → DataStore 재방출 → 같은 Balance로 `Rebalance.plan` 재계산(네트워크 없음). 키 값 = `iem_cd` 또는 `Rebalance.CASH` → bp. 계좌별 분리. 보유하지 않은 종목 키는 무시. v1은 **보유 종목 + 현금만**. `wipe()`가 전부 지운다.

## 5 NH API 클라이언트 규칙 (`NhApi` 내부, 전부 한 파일)

```kotlin
private suspend fun token(rejected: String? = null): String = tokenMutex.withLock {
    val s = vault.secrets()                                                   // 잠김 → IllegalStateException
    val key = s.appKey ?: throw NhException("AUTH", "no appkey"); val sec = s.appSecret ?: throw NhException("AUTH", "no appsecret")
    val t = s.token; val now = System.currentTimeMillis()
    if (t != null && t != rejected && s.tokenExpiresAt > now) return@withLock t   // 캐시 히트(누가 이미 갱신한 경우 포함): 발급 없음
    if (t != null && t == rejected && now - s.tokenIssuedAt < 3_600_000L)         // 발급 1 h 미만 토큰이 거부됨 = 자격/권한/호스트 문제, 만료 아님 → 재발급 금지
        throw NhException("HTTP401", "token rejected")
    withContext(NonCancellable) {                                             // 응답 수신~저장 사이 취소로 발급 토큰 유실 → 재발급 방지(HttpTimeout은 여전히 유효)
        val r = issueToken(key, sec)                                          // 앱 전체 유일한 /oauth2/token 호출 지점
        val ttl = (r.expiresIn.takeIf { it > 0 } ?: 86_400) * 1_000 - 60_000 // 60 s 조기 만료
        val at = System.currentTimeMillis()
        vault.update { it.copy(token = r.accessToken, tokenIssuedAt = at, tokenExpiresAt = at + ttl) }
        r.accessToken
    }
}
private suspend fun issueToken(appKey: String, appSecret: String): TokenRes {
    val r = loadResult {
        client.post(TOKEN_URL) {                                              // POST 명시(submitForm(encodeInQuery)는 GET)
            url { parameters.append("appkey", appKey); parameters.append("appsecretkey", appSecret)
                  parameters.append("grant_type", "client_credentials"); parameters.append("scope", "oob") }
            setBody(FormDataContent(Parameters.Empty))                        // Content-Type: x-www-form-urlencoded, 바디 비움
        }                                                                     // Authorization 헤더 없음
    }.getOrElse { throw IOException(it::class.simpleName) }                  // cause 미첨부(타임아웃 메시지의 appkey URL 차단); 오프라인 = "네트워크 오류"(AUTH 아님); Cancellation은 재던짐
    if (!r.status.isSuccess()) throw NhException("HTTP${r.status.value}", "token")
    return loadResult { NhJson.decodeFromString<TokenRes>(r.bodyAsText()) }.getOrElse { throw NhException("AUTH", "bad token body") } // 바디 미포함
}
private suspend fun call(path: String, body: JsonObject, cts: String?): HttpResponse {
    var tok = token(); var retried401 = false; var attempt = 0
    while (true) {
        val r = client.post(REST + path) {
            bearerAuth(tok); setBody(TextContent(body.toString(), ContentType.Application.Json))   // ContentNegotiation 없음
            if (cts != null) { header("cts", cts); header("cts_flag", "Y") }
        }
        when {
            r.status == HttpStatusCode.Unauthorized && !retried401 -> { retried401 = true; tok = token(rejected = tok) }  // 재발급 최대 1회(1 h 창 밖일 때만), 재시도 1회
            r.status == HttpStatusCode.TooManyRequests && attempt < 3 -> delay(300L shl attempt++)   // 300/600/1200 ms, 토큰 불변
            else -> return r
        }
    }
}
private suspend inline fun <reified A, reified B> pages(path: String, input: JsonObject): List<NhResponse<A, B>> {
    val out = mutableListOf<NhResponse<A, B>>(); var cts: String? = null
    while (true) {
        val r = call(path, input, cts)
        if (!r.status.isSuccess()) throw NhException("HTTP${r.status.value}", r.status.description)
        val res = NhJson.decodeFromString<NhResponse<A, B>>(r.bodyAsText())
        Log.d("NhApi", "$path rsp_cd=${res.rspCd} ${res.rspMsg}")            // 유일한 네트워크 로그; release는 R8이 제거(BuildConfig 가드 없음)
        if (res.output0 == null && res.output1 == null && !res.ok) throw NhException(res.rspCd, res.rspMsg)   // 2페이지 이후 오류 바디도 잘림 없이 실패
        out += res
        val next = r.headers["cts"]
        if (r.headers["cts_flag"] != "Y" || next.isNullOrEmpty() || next == cts) return out   // cts 반복 시 중단
        cts = next
    }
}
```
- **발급 규칙(브리프 그대로)**: 유효 토큰 없음(없음/만료) → 발급; 401 → 뮤텍스 안에서 "내가 보낸 토큰 == 저장 토큰" **이고 발급 1 h 경과**일 때만 재발급 → 동시·순차 겹침 401 모두 발급 1회, 지속 401(권한·IP 제한)은 화면당 재발급이 아니라 시간당 최대 1회; 429 → 지연만; IOException/타임아웃 → **재시도 없음**(`HttpRequestRetry` 미설치 + OkHttp `retryOnConnectionFailure(false)` — 이중 발급 경로 0); 발급~저장은 `NonCancellable`. `tokenIssuedAt`은 blob에 있으므로 프로세스 재시작 후에도 창이 유지된다.
- **성공 판정**: `expect(block, empty)` 하나 + `pages`의 페이지별 오류 바디 검사. 블록 존재 = 성공; 부재 + `ok` = 빈 결과; 부재 + 실패 = `NhException(rsp_cd, rsp_msg)`. 선택 블록(Output_1) 부재 = `orEmpty()`.
- **페이지네이션**: 응답 헤더 `cts`/`cts_flag` → 다음 요청 헤더 echo. 단일 함수, 두 엔드포인트 공유.
- **Throttle 없음**: 화면당 단일 호출 + 300 ms debounce. 트리거: 한 화면이 >4 호출로 fan-out 하면 `Semaphore(4)` 1개.
- **로깅**: `ktor-client-logging` 의존성 자체가 없음(컴파일러가 금지). `Log.e/w` 호출 0개. `Log.d/v`는 R8 `-assumenosideeffects`로 제거(단일 메커니즘).
- 자격증명 변경: `SettingsViewModel`이 `trim()`·비어있지 않음·인쇄 가능 ASCII(`code in 33..126`)를 `require`한 뒤 `vault.update { it.copy(appKey = k, appSecret = s, token = if (same) it.token else null, tokenIssuedAt = if (same) it.tokenIssuedAt else 0, tokenExpiresAt = if (same) it.tokenExpiresAt else 0) }` — 붙여넣기 공백 차이로 "변경"이 되어 재발급되는 일 없음. 메모리 토큰 홀더가 없으므로 `clearToken()` 자체가 없다.

## 6 리밸런스 계산 (`Rebalance.plan`, 순수, Long 정수 연산)

```
cash         = Output_0.nxt2_dd_dca                     // D+2 예수금: KRX T+2 결제라 당일 매매가 즉시 반영(dca(D+0)는 이틀간 불변 → total 이중 계산). 사용자 확인 §14
total        = cash + Σ holding.evalAmt                 // 확정 분모(사용자 결정 5). tot_aet_amt 아님
weightBp(h)  = if (total == 0) 0 else evalAmt * 10_000 / total
targetAmt(h) = total * bp / 10_000                      // ponytail: total > 9.2e14 KRW에서 Long 오버플로 — 개인 계좌 범위 밖
targetShares = targetAmt / price                        // FLOOR(정수 나눗셈): 목표 초과 매수 없음, 나머지는 예수금
deltaShares  = targetShares - qty   (+ = 매수, − = 매도, 0 = "—"); 목표 없음 || price ≤ 0 → null("—", cashAfter 제외)
cashAfter    = cash − Σ (deltaShares × price)
CASH 행      = 마지막 Line(code = Rebalance.CASH, currentAmt = cash, weightBp, targetBp = targets[CASH], deltaShares = null) — 목표 편집 가능. 종목코드 "CASH"인 보유는 일반 행
targetSumBp  = Σ targets(보유 종목 + CASH; 미보유 키 무시); 개별 bp는 0..10_000 (require)
표시: 푸터에 targetSumBp; > 10_000 빨강 "목표 합계 초과", < 10_000 황색 "합계 미달"(계산은 계속); cashAfter < 0 빨강 "예수금 부족"
빈 포트폴리오: holdings = [] → CASH weightBp = 10_000(cash > 0) / total == 0 → 전부 0, UI는 "—"
```

## 7 보안

**키 재료** (AndroidKeyStore, 비추출, StrongBox 요청 후 `StrongBoxUnavailableException` → TEE 폴백):
- `nh_pin_mac`: HMAC-SHA256, PURPOSE_SIGN, `setUnlockedDeviceRequired(true)`(루트+잠긴 기기 대입 차단; 실기기 확인 §14). **`setPin`에서만 생성**(generateKey = 덮어쓰기); `unlockWithPin`은 `getKey ?: VaultCorruptException` — 키 소실(OEM Keystore 손실·변조)이 "틀린 PIN"으로 위장되지 않음.
- `nh_bio`: AES-256/GCM, `setUserAuthenticationRequired(true)`, `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`, `setInvalidatedByBiometricEnrollment(true)`. `setUnlockedDeviceRequired` 없음(per-use 생체 키엔 잉여이고 Android 12대 "device locked" 오류의 알려진 원인). `BiometricPrompt(CryptoObject(cipher))`로만 사용. `Biometric.available()`이 false면 등록 제안·토글 자체를 안 보임(Class 3 미등록 기기에서 generateKey 예외 없음).
- **DEK**: 32B SecureRandom, PIN 설정 시 생성. 디스크엔 래핑본만: `K.DEK_PIN = seal(KEK, DEK)`, `KEK = HMAC(pbkdf2(pin, salt, iters))`; 지문 등록 시 `K.DEK_BIO = nh_bio.encrypt(DEK)`. `Secrets` blob은 `seal(DEK, …)`로 `K.SECRETS` 1개. **PIN 없이 지문만으로는 어떤 경로도 없고, 지문 없이 PIN은 항상 동작**.
- **위협 모델(README에 그대로)**: ① DataStore 복사본만 → 오프라인 공격 불가(HMAC 키가 하드웨어를 못 떠남). ② 루트 + 잠금 해제된 기기 + 앱 잠김 → 10⁶ Keystore HMAC 호출이 상한(TEE ≈ 20분–3시간, StrongBox ≈ 1–2일); 기기 잠금 중엔 `setUnlockedDeviceRequired`가 차단. ③ 앱 잠금 해제 중 메모리 덤프 → 전부. **PBKDF2는 상한이 아니다**(salt·iters가 파일에 있어 10⁶ PIN 공간은 오프디바이스 사전계산) → 10k 고정(해제 지연 ≈ 수십 ms), 저장 반복수 필드는 유지. 진짜 상한이 필요해지면 `nh_pin_mac`에 `setUserAuthenticationParameters(timeout, DEVICE_CREDENTIAL|BIOMETRIC_STRONG)`(Gatekeeper 스로틀) — v1 아님.
- **PIN 검증 = DEK_PIN GCM 태그 검사**. `unlockWithPin`(`pinMutex` 안, fail closed):
  ```
  1. p0 = store.data.first(); p = if (bootCount() != p0[LOCK_BOOT]) store.edit { LOCK_ELAPSED = elapsed() + lockoutMillis(fails); LOCK_BOOT = bootCount() } else p0   // 재부팅은 잠금을 줄이지 못함; 이후 단계는 edit가 돌려준 최신 p 사용(재부팅 직후 가짜 LockedFor 없음)
  2. if (elapsed() < p[LOCK_ELAPSED]) return LockedFor(p[LOCK_ELAPSED] - elapsed())
  3. salt/iters/DEK_PIN 중 하나라도 부재 → VaultCorruptException
  4. fails = store.edit { fails += 1; LOCK_ELAPSED = elapsed() + lockoutMillis(fails) }[FAILS]   // 검증 전에 선기록; 쓰기 실패 → 예외(HMAC 호출 안 함)
  5. withContext(Dispatchers.Default) { kek = hmac(pbkdf2(pin, salt, iters), create = false) ?: throw VaultCorruptException(); pin.fill(0)
       dek = try { open(kek, DEK_PIN) } catch (e: AEADBadTagException) { null } catch (e: Exception) { throw VaultCorruptException() }; kek.fill(0) }   // 오직 태그 불일치만 "틀림"; 잘린 blob 등은 손상
  6. dek != null → store.edit { fails = 0; remove LOCK_* }; this.dek = dek → Ok. else → Wrong(max(0, 5 - fails))
  ```
  시계: `elapsedRealtime`(단조) + `BOOT_COUNT`만. 10회 후 자동 wipe는 자기 DoS라 생략(상한 1 h). `VaultCorruptException` → §11 wipe 제안.
- **약한 PIN 거부**: `weakPin`. **PIN 변경·지문 등록은 기존 PIN 재검증 후**: `PinFlow(Verify → Setup → Confirm)`.
- **PIN 상태 보관**: PinFlow 단계와 첫 입력 PIN은 `LockViewModel` 필드(`CharArray`, 확인/취소/`onCleared`에 `fill(0)`). `rememberSaveable`/`SavedStateHandle` 금지(`lock/`·`settings/`; CI grep) — PIN·키가 시스템 saved-state Bundle에 직렬화되지 않음. Setup 중 프로세스 사망 = Setup 재시작.
- **지문 우회 불가**: CryptoObject 출력이 곧 DEK. `KeyPermanentlyInvalidatedException` → `DEK_BIO` 삭제 + "지문이 변경되어 PIN으로 해제 후 다시 등록".
- **잠금 정책**: `onStop`에 `stoppedAt = elapsedRealtime()`, `onStart`에 `elapsedRealtime() - stoppedAt >= 60_000`이면 `vault.lock()`. 지연 코루틴 없음(딥슬립 중 `delay`는 멈춰 화면 끄고 30분 뒤 복귀해도 안 잠기던 문제). `lock()` → `unlocked=false` → 게이트 오버레이 + `secretsFlow`가 `Secrets()` 방출 → 토큰 null → 소켓 세션 즉시 취소(**구조적**, 상수 간 타이밍 의존 없음). 진행 중 REST는 `secrets()`의 `IllegalStateException`으로 조용히 실패.
- **잠금 상태 = 프로세스 메모리의 DEK 뿐, 토큰 홀더 없음.** 토큰은 봉인 blob 안에만 있고 `token()`이 매 호출 `vault.secrets()`로 읽는다. 인정하는 잔여: 이미 디코드된 불변 String(토큰·키)의 GC 전 힙 잔류 — README에 명시.
- **DEK 동시성**: `@Volatile dek`; `lock()`은 참조 교체 후 `fill(0)`; `decode()`는 참조 1회 복사. `open` 중 `AEADBadTagException` → `dek == null`이면 `IllegalStateException("locked")`, 아니면 `VaultCorruptException`(cause 없음). `Secrets` JSON 디코드 실패도 `VaultCorruptException`.
- **PIN 처리**: 커스텀 `PinPad`(IME 없음), `CharArray`, HMAC 입력 직후 `fill(0)`; 파생·KEK는 `Dispatchers.Default`(메인 스레드 잼 없음).
- **탭재킹 / 자동완성**: `window.decorView.filterTouchesWhenObscured = true` + `window.setHideOverlayWindows(true)`(minSdk 31 → 무조건 호출) + `window.decorView.importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`(서드파티 자동완성이 appsecret 저장 제안 못 함). 모든 PinFlow는 액티비티 창(별도 창 없음). FLAG_SECURE와 함께 4줄.
- **설정 화면 write-only**: "저장됨/미설정"만. 마스킹 부분 표시도 없음. 입력 검증 §5.
- **플랫폼**: `allowBackup=false` + `dataExtractionRules`, FLAG_SECURE, cleartext off, 커스텀 TrustManager 없음(2026-08-29 전체 체인 검증), DataStore `corruptionHandler`(손상 = Setup 게이트, 크래시 루프 아님), minSdk 31, release `isMinifyEnabled = true; isShrinkResources = true; isDebuggable = false`. 루트 탐지 생략(README 한계 명시).

## 8 실시간 + 포그라운드 서비스 seam

**지금** — `NhApi.fills()`는 토큰의 함수:
```kotlin
internal fun fillsFrom(ws: String): Flow<Fill> {
    var streak = 0
    return vault.secretsFlow.map { it.token }.distinctUntilChanged()
        .flatMapLatest { t -> if (t == null) emptyFlow() else flow {           // 토큰 없음/잠김 → 세션 취소·대기(스핀 없음); 토큰 교체 → 세션 재시작
            val token = token()                                                 // 만료면 여기서 발급
            client.webSocket(ws) {                                              // 경로 /websocket, HTTP 인증 헤더 없음
                for (tr in CHANNELS) send(Frame.Text(subscribeFrame(token, tr)))
                for (f in incoming) { streak = 0; (f as? Frame.Text)?.let { parseFill(it.readText()) }?.let { emit(it) } }   // 리셋은 첫 프레임 수신 시(구독 송신이 아님) → 즉시 Close 반복이 1 s 루프가 안 됨
            }
            throw NhException("WS", "closed")                                   // 정상 Close도 재연결 대상
        } }
        .retryWhen { _, _ -> delay(backoffMs(streak++) + Random.nextLong(500)); true }   // 체인 끝: secretsFlow/decode 예외도 크래시 대신 백오프
}
```
- 유일한 수집자 `PortfolioViewModel.ui`(`WhileSubscribed(5_000)`). 이탈 5 s → 취소 → 세션 종료. 라이프사이클 코드 0줄.
- 만료 토큰: REST 401/만료 경로가 `vault.update` → `secretsFlow` → `distinctUntilChanged` → `flatMapLatest`가 구 세션 취소·새 토큰으로 재구독. 소켓 자체의 인증 거부 프레임 처리는 §14.1 확인 후 1줄(`rejected = token` → 다음 시도에 `token(rejected)`; 단순 Close에는 절대 아님).
- `parseFill`: `header.tr_cd ∈ CHANNELS && body` → `FillDto` 디코드(필수 3필드) → `toFill()`(`toLongOrNull`), 전체 `runCatching` → 실패 = null. ack/d3/오류/비숫자 → null, 연결 유지. 비-체결 프레임은 DEBUG에서 `rsp_cd/rsp_msg`만 로그(§14 관찰용).
- 죽은 소켓: OkHttp `pingInterval(30 s)`(RFC 6455). NH pong 응답은 §14 스모크.
- 세션 예산: 수집자 1 = 세션 1, 구독 `CHANNELS.size`. unsubscribe 불필요(취소가 닫음).

**포그라운드 서비스가 올 때 바뀌는 것(정확히)**:
1. `NhApi`: `private val shared by lazy { fillsFrom(WS).shareIn(appScope, SharingStarted.WhileSubscribed(5_000)) }`; `fun fills() = shared` — 화면+서비스가 소켓 1개 공유. `appScope` Koin single. ~3줄.
2. `realtime/FillService : LifecycleService`(foregroundServiceType=dataSync, 권한 3개, Settings 토글). `lifecycleScope`에서 `api.fills()` 수집 → 체결당 알림 1건. 토큰 갱신은 세션 안 `token()`이 이미 처리.
3. **잠금 정책 분기**: `App.kt`의 onStart 판정을 `if (!FillService.running) vault.lock()`으로; `AppNav` 게이트는 새 `screenLocked: MutableStateFlow<Boolean>`(60 s 경과 시 무조건 true)을 읽음. "UI 게이트"와 "DEK 존재"의 분리. ~6줄. 소켓 취소가 `secretsFlow`에 묶여 있으므로 서비스가 도는 동안은 DEK 유지 = 소켓 유지.
4. 보안 결과 명시: 사용자가 알림을 **켠 동안만** DEK가 프로세스 메모리에 잔류(opt-in). 끄면 즉시 기본 자세.
- 미리 만들지 않는 것: shareIn 맵, appScope, 서비스, 알림 채널, 권한, screenLocked.

## 9 해외주식 seam (gbstock)

seam은 데이터 계약: `Holding`/`Balance`는 시장 무관·KRW Long, `Rebalance`/`PortfolioUi`/화면은 이것만 본다. **spec으로 확인한 사실**: `/gbstock/inquiry/v1/balance`의 Output_0에 `krw_dca`(int64), Output_1에 `krw_eal_amt`·`krw_avg_phs_pr`·`end_pr`(int64 KRW)·`cns_bse_bnc_qty`(int64)·`eal_pft_rt` → **FX 배관 불필요, 수량 정수**. 해외 체결통보 `d0`는 7070(같은 세션). `Rebalance.CASH = "$CASH"`라 US 티커 "CASH"와 충돌하지 않는다(테스트 존재).
추가 시 손대는 곳(전부 additive):
- `api/NhApi.kt`: `+ private GbSummaryDto/GbHoldingDto` + `toHolding()`; `+ suspend fun gbBalance(acct): Balance`(같은 `pages`/`expect`); `CHANNELS = listOf("d2", "d0")` + `parseFill` tr_cd 분기(바디가 다르면 `GbFillDto`).
- `model/Model.kt`: `+ enum class Market { KR, GB }` + `Holding.market`(오늘은 상수라 부재).
- `portfolio/PortfolioScreen.kt`: `mapLatest` 1줄 → `coroutineScope { val kr = async { api.balance(acct) }; val gb = async { api.gbBalance(acct) }; Balance(kr.cash + gb.cash, kr.holdings + gb.holdings) }`. `token()` 뮤텍스가 동시 401을 1회 발급으로 유지(테스트 존재).
- `PortfolioScreen`/`Format`: 시장 칩. 분모는 KRW 유지.
- 전제(명시): gbstock `cur_cd="KRW"` 조회의 KRW 환산(당일매매기준환율)을 그대로 사용; 소수점 수량은 spec상 int64라 발생하지 않음 — 바뀌면 `RebalanceTest`가 안전망.
- 미리 만들지 않는 것: AssetClass/BalanceSource 인터페이스, 시장별 저장소, 통화 값 타입, FX 배관, `Holding.market`.

## 10 UI 화면 & 내비게이션 & organic 테마

- **내비게이션**(`MainActivity.kt`): `@Serializable sealed interface Route { Accounts; Portfolio(no); Settings }` + §4 게이트. start는 항상 `Accounts`. 잠금 화면·"키 없음" Settings·설정발 PinFlow는 Route가 아니라 게이트/오버레이(Back 불가; Verify는 `BackHandler {}`). 딥링크도 게이트 뒤.
- **PinFlow / LockScreen**: 6개 점 + `PinPad`(0–9/←) + 지문 버튼(`enrolled`) + 잠금 카운트다운(`LockedFor`). `PinMode { Setup, Verify, Change }`; `Change = Verify(old) → Setup → Confirm`. 약한 PIN → 인라인 오류. 최초 설정 직후 `Biometric.available`일 때만 지문 등록 1회 제안. 단계·첫 입력은 `LockViewModel`(§7). **BiometricPrompt는 컴포저블의 `rememberCoroutineScope()`에서 launch**(회전 시 취소 → 버튼 재탭; viewModelScope에서 기다리면 죽은 Activity를 붙들고 영원히 안 돌아옴).
- **AccountsScreen**: 운영 계좌 목록(acct_type 01·02만; 모의 03은 `accounts()`가 이미 제외), 빈 목록 "연결된 계좌가 없습니다", 에러 + 재시도(`retry()`).
- **PortfolioScreen**: 헤더 카드(총평가 = total, 예수금(D+2)). `보유 | 리밸런스` 세그먼트(`remember`, 저장 안 함). 행: 종목명 · 보유수량 · 수익률(`Double.pct()`) · 평균매입가 · 현재가 · 잔고수량 · 평가금액 · 자산비중; 리밸런스 모드: 목표 % · Δ주수. 현금 행 마지막(목표 편집 가능). 하단 배지(합계 초과/미달, 예수금 부족). 에러는 배너(마지막 정상 표 유지), 전체 화면 에러는 `balance == null && error != null`일 때만. pull-to-refresh, 체결 스낵바(시각 포함). "실시간 점" 없음(데이터 소스가 없는 UI 요소는 두지 않음).
- **SettingsScreen**(`requestPin: (PinMode, (Boolean) -> Unit) -> Unit` 인자; 게이트 단계와 Route 양쪽에서 같은 컴포저블): appkey/secret 비밀번호 필드(trim·비어있지 않음·인쇄 가능 ASCII 검증, 인라인 오류; 저장 후 비움, "저장됨/미설정"만 표시), 지문 토글(`Biometric.available`일 때만 표시; `requestPin(Verify) { ok -> if (ok) scope.launch { biometric.enroll(activity) } }`), PIN 변경(`requestPin(Change)`), 초기화(`wipe()` 확인 다이얼로그 → 자격증명·토큰·목표 비중·PIN 전부 삭제 → Setup 게이트로).
- **organic M3 테마**(`ui/Theme.kt`, 안정 컴포넌트만, dynamic color 끔): Moss `#3F6B4A` primary · Sage `#DDE8D6` primaryContainer · Fern `#A8C8A0` dark primary · Clay `#9C6B45` secondary · Sand `#F6F1E7` background/surface · Bark `#2B2A26` onSurface/dark background · Ember `#B3412F` error. `lightColorScheme/darkColorScheme` + `isSystemInDarkTheme()`. `Shapes(8, 14, 20, 28, 36 dp)`. 시스템 sans(한글 Noto Sans CJK 폴백), 숫자 `tnum`. 손익 색 `plColor()`(이익 빨강, 손실 파랑).
- **Format.kt**: `DecimalFormatSymbols(Locale.KOREA)` 고정(기기 로케일 무관) — `Long.krw()`, `Long.shares()`, `Int.bpPct()`, `Double.pct()` = `DecimalFormat("+#,##0.00;-#,##0.00")` + "%"(`pft_rt` 단위 §14 확인 후 ×100 여부 1줄).

## 11 에러 처리

- 원칙: fail fast, 삼키지 않음, 비밀 절대 미포함.
- `NhException(code, message)`: `code ∈ {rsp_cd, "HTTP<status>", "AUTH", "WS"}`. `Throwable.userMessage()`(ui/Format.kt): `AUTH`(키 없음/토큰 바디 오류)/`HTTP400`/`HTTP401`(토큰 요청 거부·재발급 후 재401·1 h 창 내 거부) → "인증 실패 — 설정에서 앱 키를 확인하세요"(Settings 링크); `HTTP429` → "요청이 많습니다. 잠시 후 다시"; `IOException`(토큰 요청 실패 포함) → "네트워크 오류"; `SerializationException` → "응답 형식 오류"(메시지 미표시); `VaultCorruptException` → wipe 제안 다이얼로그(자동 wipe 아님); `IllegalStateException`(잠김) → 표시 없음(게이트가 처리); `IllegalArgumentException`(입력 검증) → 인라인; 그 외 `NhException` → rsp_msg 그대로.
- ViewModel: `loadResult` + `runningFold`로 마지막 정상 `Plan` 유지 + 배너. 잠금과 수집의 관계는 타이밍 불변식이 아니라 구조: `lock()` → `secretsFlow`가 `Secrets()` 방출 → 소켓 취소; REST는 `secrets()` 예외 → `loadResult` → 표시 없음.
- 소켓: 무한 `retryWhen`(정상 Close 포함, 체인 끝에 위치 → 어떤 예외도 프로세스 크래시가 아님). 파싱 실패는 프레임 단위 무시.
- 로그: `Log.d` 한 줄(path, rsp_cd, rsp_msg) + DEBUG 소켓 비-체결 프레임의 rsp_cd/rsp_msg + DEBUG d2 accountno 뒤 4자리. `Log.e/w` 없음. release는 R8 제거.

## 12 테스트 & CI

JVM 단위 테스트만: `kotlin("test")` + JUnit4, `kotlinx-coroutines-test`, `ktor-client-mock`, `ktor-server-cio` + `ktor-server-websockets`(embedded 서버, 포트 0), `datastore-preferences-core`(임시 파일). `testOptions.unitTests.isReturnDefaultValues = true`(`Log.d` 스텁). 인터페이스/페이크 없음.
- **RebalanceTest**: 분모 = Σeal_amt + cash; bp 가중치; FLOOR(10.99주 → 10); price 0 → delta null; 목표 없음 → null; CASH 마지막; **종목코드 "CASH" 보유는 현금 행 아님**; bp 범위 밖 → `IllegalArgumentException`; 합계 초과/미달; cashAfter 음수; 빈 포트폴리오; 시드 랜덤 200회: Σ targetShares×price ≤ total.
- **NhApiTest**(MockEngine + JVM `Vault`): acctinfo 01/02/03/99 → 01·02만 반환(03·99 제외); **acctinfo 2페이지 → 합산**; Output_1 부재 → 빈 보유; Output_0 부재 + "정상처리완료" → 빈 결과; 부재 + "오류" → `NhException(rsp_cd)`; **2페이지째 오류 바디 → `NhException`(부분 결과 없음)**; 2페이지 cts(Y→N, 헤더 echo, 반복 시 중단); 콜드 스타트 → `/oauth2/token` 정확히 1회(POST, form content-type, 쿼리 4개, Authorization 없음); 저장 토큰 `tokenExpiresAt = Long.MAX_VALUE` → 발급 0회; `= 0` → 1회, 저장된 `tokenExpiresAt`가 `[now+86_340_000 ± 5 s]`; 401 → 재발급 1회 + 재시도 성공 + `tokenIssuedAt` 저장; 동시 401 2건 → 발급 1회; 순차 겹침 401 → 1회; **재발급 후 재401 → `NhException("HTTP401")`; 다음 `call()`도 발급 0회(1 h 창); `tokenIssuedAt = now - 2 h` 시드 + 401 → 재발급 1회**; **토큰 응답 직후 호출자 취소 → 저장 토큰 존재, 두 번째 호출 발급 0회**; 429×2 후 200 → 발급 0회, 지연 300/600; **토큰 요청 IOException → `IOException`, 메시지·cause에 "appkey" 없음**; 토큰 바디 malformed → `NhException("AUTH")` 바디 없음; 토큰 4xx → `NhException("HTTP4xx")`; `lock()` 후 호출 → `IllegalStateException`, HTTP 0건; 동일 키 재저장(앞뒤 공백 포함) → 발급 0회.
- **NhSocketTest**(embedded CIO WS 서버): 구독 프레임이 리터럴 `{"header":{"token":"T","tr_type":"1"},"body":{"tr_cd":"d2","tr_key":""}}`과 JSON 동치; d2 프레임 → `Fill`("0000000005"→5, "00000035550"→35550, time); **header.tr_cd=d2 + body{rsp_cd,rsp_msg}(ack) → null, 재조회 트리거 없음**; d3/쓰레기 → null; **d2 `concgty=""` → null, 연결 유지**; 프레임 1개 후 Close → `backoffMs(0)` 후 재연결; **첫 프레임 수신 시 streak 리셋 — 구독 직후 Close 반복은 백오프 상승**; `vault.update(token 교체)` → 새 토큰으로 재구독; **`vault.lock()` → 세션 즉시 취소, 재연결 0건; unlock → 재구독**; 토큰 null → 연결 0건; `backoffMs` 수열·상한.
- **CryptoTest**: seal/open 왕복, 변조 → `AEADBadTagException`, 틀린 키, pbkdf2 결정성/솔트/반복수, `lockoutMillis` 표(**54·69·1000 → 3_600_000**), `weakPin` 표.
- **VaultTest**(JVM DataStore + 가짜 hmac/elapsed/bootCount): setPin→unlock 왕복; 약한 PIN 거부; 틀린 PIN → Wrong(remaining); 5회 → LockedFor 상승, 성공 시 초기화; elapsed만 전진해야 해제; bootCount 변경 → 재무장, **재부팅 직후 첫 시도가 가짜 LockedFor 아님**; **동시 unlock 2건 → fails +1**; hmac 예외 → fails 이미 증가; **hmac null(키 소실)·잘린 blob·salt 부재 → `VaultCorruptException`(Wrong 아님)**; lock 후 `secrets()` 예외, `secretsFlow`가 `Secrets()` 방출; 변조 blob → `VaultCorruptException`; **wipe → `unlocked=false`, hasPin=false; wipe 후 setPin의 DEK로 옛 blob 못 엶**; CharArray 소거; `K.PBKDF2_ITERS` 저장·사용.
- **FormatTest**: `krw/shares/pct` 각 1행(로케일 고정).
- **정적 검사**: ktlint(`ktlint_official`, Composable 네이밍 예외) + detekt 1.23(기본 룰 + **`ForbiddenImport` 1개**: `includes: ['**/model/**', '**/portfolio/Rebalance.kt']`, `imports: ['android.*', 'androidx.*', 'io.ktor.*', 'org.koin.*', 'kotlinx.serialization.*']` — 1.23은 룰당 설정 블록 1개; `api/` 울타리·logging 금지는 삭제(각각 private DTO·의존성 부재가 이미 강제), MaxLineLength 120), Kotlin `-Werror` + `optIn`(§13), CI grep 1줄: `lock/`·`settings/`에 `rememberSaveable` 없음.
- **CI** `.github/workflows/ci.yml`: push/PR → ubuntu-latest, temurin 21, `gradle/actions/setup-gradle`(캐시), `./gradlew ktlintCheck detekt testDebugUnitTest assembleDebug assembleRelease --no-daemon`(release 미서명; R8·keep 룰·`lintVitalRelease`), 실패 시 `app/build/reports` + 항상 `app/build/outputs/mapping/release` 아티팩트. 비밀 없음. 로컬: `JAVA_HOME=<Android Studio>/jbr`.
- **기기 스모크 체크리스트**(README, 릴리스당 1회, **minify된 `assembleRelease` APK를 debug 키로 서명해 설치** — private DTO·reified `NhResponse`·`@Serializable Route`는 R8 full mode가 깨는 지점): §14 항목 전부 + Keystore 생성·StrongBox 폴백, Class 3 지문 + CryptoObject, 지문 재등록 무효화, 8443/7070 TLS, 설치 후 토큰 발급 1회 관찰, 나무 앱 수동 매매 → d2 → ~1 s 내 재조회 + 예수금 즉시 감소, 스크린샷/오버레이 차단, 화면 끄고 2분 → 복귀 시 잠금.

## 13 의존성 목록 (`gradle/libs.versions.toml` 단일 관리; 핀 시점 최신 안정판)

| 항목 | 버전 |
|---|---|
| AGP | 9.1.1 |
| Kotlin + serialization plugin + compose compiler(번들) | 2.3.x |
| compileSdk / targetSdk / minSdk | 37 / 37 / **31** |
| androidx.compose BOM (`ui`, `material3`, `ui-tooling-preview`) | 2026.08.00 |
| androidx.activity-compose | 1.12.x |
| androidx.navigation-compose | 2.9.x |
| androidx.lifecycle (`viewmodel-compose`, `runtime-compose`, `process`) | 2.9.x |
| androidx.datastore-preferences (+ `-core` test) | 1.1.x |
| androidx.biometric (fragment 전이 포함) | 1.4.x |
| io.ktor `client-core`, `client-okhttp`, `client-websockets`; test: `client-mock`, `server-cio`, `server-websockets` | 3.x (3.2+) |
| kotlinx-serialization-json | 1.9.x |
| kotlinx-coroutines-android / -test | 1.10.x |
| io.insert-koin `koin-android`, `koin-androidx-compose` | 4.1.x |
| detekt / ktlint-gradle | 1.23.x / 13.x |
| JDK | 21 |

`app/build.gradle.kts` 필수 항목: `kotlin { compilerOptions { allWarningsAsErrors = true; optIn.addAll("kotlinx.coroutines.FlowPreview", "kotlinx.coroutines.ExperimentalCoroutinesApi", "androidx.compose.material3.ExperimentalMaterial3Api") } }`(debounce/mapLatest/flatMapLatest/PullToRefreshBox), `testOptions.unitTests.isReturnDefaultValues = true`. `buildFeatures.buildConfig`는 켜지 않음(`BuildConfig` 참조 0개).
제거됨(개정 1 대비): `ktor-client-auth`, `ktor-client-content-negotiation`, `ktor-serialization-kotlinx-json`, `androidx.fragment-ktx`. 의도적으로 없음: Room, security-crypto, Tink, Timber, `ktor-client-logging`, OkHttp logging-interceptor, MockK, Robolectric, Compose UI test.

## 14 미해결/확인 필요

**실기기 확인(스모크 체크리스트에 그대로 들어감)**
1. **WS ack/오류 프레임 형태**: spec은 `rsp_cd=WSS10006`가 "반환된다"고만 한다. ack/오류의 JSON 위치를 DEBUG 로그로 기록 → 확인 후 (a) 인증 오류 코드 → `rejected = token` 후 다음 시도에 `token(rejected)` 1줄, (b) NH가 ack를 전혀 안 주면 streak 리셋을 "연결 10 s 유지"로 대체(`launch { delay(10_000); streak = 0 }` 1줄).
2. **열린 소켓의 토큰 만료 시 NH 동작**(Close? 무응답?): Portfolio를 열어둔 채 토큰 갱신 강제 → d2 계속 수신 확인. 무응답이면 `PortfolioViewModel`에 1 h ticker 1줄.
3. **d2 `accountno` vs `acct_no` 형식**: 뒤 4자리 DEBUG 로그로 숫자 정규화 매칭 확인. 불일치여도 재조회는 동작, 스낵바만 영향.
4. **bnc_bse_cd "1" vs "5"**: "1"에서 `eal_amt`가 `now_pr` 기준인지, 당일 체결이 즉시 반영되는지. 상수 1개 교체.
5. **`nxt2_dd_dca`가 당일 체결을 즉시 상계하는가**: 나무 수동 매수 → d2 → 재조회 → 예수금이 체결금액만큼 즉시 감소. 아니면 `orr_pbl_amt.toLong()`으로 폴백(DTO 1줄).
6. **OkHttp ping에 NH가 pong을 주는가**: 5분 이상 무거래 유지 → 재연결 로그 0건. 실패 시 `pingInterval` 제거.
7. **401 의미**: 만료 토큰에 401(403 아님)인지. 403이면 `call()` 비교 1줄.
8. **`act_no` 11자리**: 실계좌 `acct_no` 길이 로그(뒤 4자리). 하이픈/짧은 형식이면 그때 정규화.
9. **`pft_rt` 단위**(12.5 vs 0.125): 보유 1건의 `pft_rt`와 `(now_pr−phs_pr)/phs_pr`를 나란히 로그 → `Double.pct()` ×100 여부 1줄.
10. **`setUnlockedDeviceRequired`(nh_pin_mac)**: 기기 잠금 → 지문으로 키가드 해제 → 앱 열고 2 s 내 PIN 입력. "device locked" 오류면 플래그 1줄 제거.
11. StrongBox 가용성·Class 3 지문 유무(없으면 `available()=false`로 PIN 전용 — 크래시 없음), `HttpTimeout`이 WS 세션에 적용되지 않음(5분 유지 테스트).
12. 실제 `rsp_cd` 샘플(운영 계좌 조회 응답)로 `OK_CODES` 보강 필요 여부.
13. R8 release APK로 전 화면 통과(§12 스모크 빌드).

**사용자 확정(2026-08-30)** — 14 minSdk 31 · 15 예수금 D+2 · 16 백그라운드 60 s 후 복귀 시 잠금 · 17 401 재발급 억제 1 h · 18 모의계좌 숨김 · 19 잠금 5회부터 30 s×2ⁿ(상한 1 h, 자동 초기화 없음). 남은 것:
20. 해외주식 도입 시 KRW 환산은 NH의 `krw_*` 값(당일매매기준환율) 그대로 — 도입 시점에 확인.
21. 핀 시점 Ktor 3.x·AGP 9.1.1·Kotlin 2.3 조합의 정확한 버전(빌드 첫날 확정).
