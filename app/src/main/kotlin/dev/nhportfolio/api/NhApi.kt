package dev.nhportfolio.api

import android.util.Log
import dev.nhportfolio.market.Bar
import dev.nhportfolio.model.Account
import dev.nhportfolio.model.Balance
import dev.nhportfolio.model.Fill
import dev.nhportfolio.model.Holding
import dev.nhportfolio.security.Vault
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.random.Random

private const val REST = "https://api.nhplug.com:8443"
private const val TOKEN_URL = "$REST/oauth2/token"

/** acctinfo 의 acct_type: 01 운영 일반, 02 운영 주문대리인. 그 외(03 모의 등)는 목록에서 제외한다. */
private val LIVE_TYPES = setOf("01", "02")

/** 재발급 억제 창 — 발급한 지 이보다 짧은 토큰이 401 이면 만료가 아니라 자격/권한 문제다. */
private const val REISSUE_WINDOW_MS = 3_600_000L

private const val TOKEN_EARLY_EXPIRY_MS = 60_000L
private const val DEFAULT_TOKEN_TTL_SEC = 86_400L
private const val RATE_LIMIT_RETRIES = 3
private const val RATE_LIMIT_BASE_DELAY_MS = 300L
private const val WS_PING_SECONDS = 30L
private const val REQUEST_TIMEOUT_MS = 15_000L
private const val HTTP_SERVER_ERROR = 500
private const val CONNECT_TIMEOUT_MS = 10_000L

private const val WS = "wss://api.nhplug.com:7070/websocket" // 통보 채널은 국내·해외 모두 7070
private const val BACKOFF_JITTER_MS = 500L
private const val BACKOFF_BASE_MS = 1_000L
private const val BACKOFF_MAX_MS = 30_000L
private const val BACKOFF_MAX_SHIFT = 5

/** NH 초당 5회(요청 간 0.2초 이상) 제한 — call() 하나가 앱 전체 요청에 동일하게 적용한다. */
private const val REQUEST_GAP_MS = 250L

/** dailyBars 수동 페이징의 반복 상한 — 서버가 계속 진전만 있는 응답을 줘도 무한정 돌지 않는다. */
private const val MAX_PAGES = 20

val NhJson: Json =
    Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

/** [code] 는 `rsp_cd` | `"HTTP<status>"` | `"AUTH"` | `"WS"`. 비밀은 절대 담지 않는다. */
class NhException(
    val code: String,
    message: String,
) : Exception(message)

/** NH 응답 봉투. 자산군과 무관하게 같은 모양이다. */
@Serializable
data class NhResponse<A, B>(
    @SerialName("rsp_cd") val rspCd: String = "",
    @SerialName("rsp_msg") val rspMsg: String = "",
    @SerialName("Output_0") val output0: A? = null,
    @SerialName("Output_1") val output1: B? = null,
)

private val OK_CODES = setOf("00000", "00166", "00221", "13578")
private val NOT_OK = listOf("않", "못", "미완료")

/** 정상 코드는 여러 개이고 API 마다 다르다 — 코드 집합 ∪ 메시지로 판정한다.
 *  메시지 판정은 부정 표현을 걸러낸다 — "완료되지 않았습니다" 도 "완료" 를 포함한다. */
val NhResponse<*, *>.ok: Boolean
    get() = rspCd in OK_CODES || ("완료" in rspMsg && NOT_OK.none { it in rspMsg })

/**
 * 블록이 있으면 성공, 없으면 [ok] 일 때만 [empty], 아니면 업무 오류.
 * 조회 0건과 오류를 구분하는 유일한 지점이다.
 */
fun <T> NhResponse<*, *>.expect(
    block: T?,
    empty: T,
): T = block ?: if (ok) empty else throw NhException(rspCd, rspMsg)

/** 앱 전체에서 예외를 Result 로 바꾸는 유일한 관용구. 취소는 그대로 던진다. */
@Suppress("TooGenericExceptionCaught")
inline fun <T> loadResult(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

/**
 * NH PLUG OpenAPI 클라이언트. HTTP·WebSocket·NH JSON 을 아는 앱의 유일한 파일이다.
 *
 * 토큰 규칙: 24시간 캐시, 401 일 때만 재발급(그것도 발급 1시간 경과 후),
 * 429 는 지연만, IO 오류는 재시도하지 않는다 — 재발급 경로를 하나로 유지해
 * NH 보안 알림을 유발하지 않기 위해서다.
 */
class NhApi(
    private val vault: Vault,
    engine: HttpClientEngine =
        OkHttp.create {
            config {
                pingInterval(WS_PING_SECONDS, TimeUnit.SECONDS)
                retryOnConnectionFailure(false) // OkHttp 자체 재전송도 금지 — 토큰 POST 이중 발급 차단
            }
        },
    /** 요청 게이트의 시계. 실제로는 실시간이지만, 테스트는 `runTest` 가상 시계를 주입해
     *  실시간 잡음(코루틴 전환 등) 없이 간격을 결정적으로 검증한다. */
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val client =
        HttpClient(engine) {
            expectSuccess = false // HTTP 상태는 진실이 아니다 — 판정은 call/pages 가 한다
            install(WebSockets)
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                connectTimeoutMillis = CONNECT_TIMEOUT_MS
            }
        }
    private val tokenMutex = Mutex()
    private val gateMutex = Mutex()

    /** 마지막으로 예약한 요청 슬롯. null 이면 아직 아무 요청도 없었다는 뜻이다(첫 요청은 대기 없음). */
    private var nextSlotAt: Long? = null

    suspend fun accounts(): List<Account> {
        val pages =
            pages<List<AccountDto>, JsonElement>(
                path = "/n2/acctinfo",
                input = buildJsonObject { put("Input_0", buildJsonObject { }) },
            )
        val first = pages.first()
        val all = first.expect(first.output0, emptyList()) + pages.drop(1).flatMap { it.output0.orEmpty() }
        return all.filter { it.type in LIVE_TYPES }.map { Account(it.no) }
    }

    suspend fun balance(acct: Account): Balance {
        val pages =
            pages<BalanceSummaryDto, List<HoldingDto>>(
                path = "/krstock/inquiry/v1/balance",
                input =
                    buildJsonObject {
                        put(
                            "Input_0",
                            buildJsonObject {
                                put("act_no", acct.no)
                                put("bnc_bse_cd", "1") // 1.주식관련 총 평가(체결기준)
                                put("ltg_aot_dit_cd", "1") // 1.상장종목
                                put("aet_bse", "1") // 1.순자산
                                put("qut_dit_cd", "UNT") // 통합시세
                            },
                        )
                    },
            )
        val first = pages.first()
        return Balance(
            cash = first.expect(first.output0, BalanceSummaryDto()).cash,
            holdings = pages.flatMap { it.output1.orEmpty() }.map { it.toHolding() },
        )
    }

    /** ETF 구성종목의 종목코드. 유니버스 조달 경로다(krstock 에 랭킹 API 가 없다). */
    suspend fun etfComponents(etfCode: String): List<String> {
        val pages =
            pages<List<ComponentDto>, JsonElement>(
                path = "/krstock/quote/v1/etfComponents",
                input = buildJsonObject { put("Input_0", buildJsonObject { put("iem_cd", etfCode) }) },
            )
        val first = pages.first()
        val all = first.expect(first.output0, emptyList()) + pages.drop(1).flatMap { it.output0.orEmpty() }
        return all.mapNotNull { shortCode(it.code) }.distinct()
    }

    /** 일봉. [count] 는 읽을 건수, [endDate] 는 YYYYMMDD(비우면 최근). 오래된 것부터 정렬해 돌려준다. */
    suspend fun dailyBars(
        code: String,
        count: Int,
        endDate: String = "",
    ): List<Bar> {
        if (count <= 0) return emptyList()
        val collected = LinkedHashMap<String, Bar>()
        var edate = endDate
        var oldestSeen: String? = null
        var page = 0
        // array_cnt 상한이 명세에 없다 — 서버가 cts 없이 자르면 가장 오래된 일자의 전날을 edate 로
        // 넣어 수동으로 이어 받는다. 요청 건수에 닿거나(count) 빈 응답이거나, 오래된 일자가 뒤로
        // 가지 않으면(진전 없음 — 반복은 물론 서버가 역행하는 경우까지) 멈춘다 — 후자가 무한 루프를 막는다.
        // 진전이 있어도 MAX_PAGES 에서는 강제로 멈춘다(page cap) — 그마저 없으면 서버가 매번
        // 새 날짜를 하나씩만 주는 병적인 응답에 갇힌다.
        while (collected.size < count && page < MAX_PAGES) {
            page++
            val remaining = count - collected.size
            val batch =
                pages<JsonElement, List<BarDto>>(
                    path = "/krstock/quote/v1/period",
                    input =
                        buildJsonObject {
                            put(
                                "Input_0",
                                buildJsonObject {
                                    put("market_cd", "UNT") // 통합시세 — balance() 의 qut_dit_cd 와 같은 기준
                                    put("iem_cd", code)
                                    put("mrkt_div_cls_code", "1") // 거래소
                                    put("gubun", "1") // 일봉
                                    put("edate", edate)
                                    put("array_cnt", remaining.toString())
                                },
                            )
                        },
                    // 서버가 array_cnt 를 무시하고 cts 로 계속 밀어주면(실측: ~230봉/페이지) 이 hop 에
                    // 필요한 만큼(remaining)만 모이는 순간 cts 를 더 따라가지 않는다 — 그 뒤는 어차피
                    // takeLast(count) 로 버려질 옛날 봉이다.
                    enough = { pages -> pages.sumOf { it.output1.orEmpty().size } >= remaining },
                )
            val first = batch.first()
            val dtos = first.expect(first.output1, emptyList()) + batch.drop(1).flatMap { it.output1.orEmpty() }
            // bsop_date 가 8자리 숫자가 아닌 행은 toBar() 가 통째로 버린다 — oldest 는 살아남은
            // 행에서만 구해야 dayBefore() 에 빈 문자열이 들어가는 일이 없다.
            val bars = dtos.mapNotNull { it.toBar() }
            for (bar in bars) collected[bar.date] = bar

            // 빈 응답이거나(oldest == null), 가장 오래된 일자가 뒤로 가지 않으면 멈춘다 — 서버가
            // 같은 값을 반복하거나(진전 없음) edate 를 무시하고 역행해도 여기서 끊는다.
            val oldest = bars.minOfOrNull { it.date }
            if (oldest == null || (oldestSeen != null && oldest >= oldestSeen)) break
            oldestSeen = oldest
            edate = dayBefore(oldest)
        }
        return collected.values.sortedBy { it.date }.takeLast(count)
    }

    /**
     * 유효한 토큰이 있으면 그대로, 없으면 발급한다. [rejected] 는 방금 401 을 받은 토큰이다.
     * 뮤텍스 안에서 "내가 보낸 토큰 == 저장 토큰" 을 확인하므로 동시·순차 401 이 겹쳐도 발급은 한 번이다.
     */
    internal suspend fun token(rejected: String? = null): String =
        tokenMutex.withLock {
            val secrets = vault.secrets()
            val appKey = secrets.appKey ?: throw NhException("AUTH", "no appkey")
            val appSecret = secrets.appSecret ?: throw NhException("AUTH", "no appsecret")
            val current = secrets.token
            val now = System.currentTimeMillis()

            if (current != null && current != rejected && secrets.tokenExpiresAt > now) return@withLock current
            if (current != null && current == rejected && now - secrets.tokenIssuedAt < REISSUE_WINDOW_MS) {
                throw NhException("HTTP401", "token rejected")
            }

            withContext(NonCancellable) {
                // 응답 수신과 저장 사이에 취소되면 발급 토큰을 잃는다
                val issued = issueToken(appKey, appSecret)
                val ttl = (issued.expiresIn.takeIf { it > 0 } ?: DEFAULT_TOKEN_TTL_SEC) * 1_000 - TOKEN_EARLY_EXPIRY_MS
                val at = System.currentTimeMillis()
                vault.update { it.copy(token = issued.accessToken, tokenIssuedAt = at, tokenExpiresAt = at + ttl) }
                issued.accessToken
            }
        }

    @Suppress("ThrowsCount")
    private suspend fun issueToken(
        appKey: String,
        appSecret: String,
    ): TokenDto {
        val response =
            loadResult {
                client.post(TOKEN_URL) {
                    url {
                        parameters.append("appkey", appKey)
                        parameters.append("appsecretkey", appSecret)
                        parameters.append("grant_type", "client_credentials")
                        parameters.append("scope", "oob")
                    }
                    // FormDataContent 를 쓰면 안 된다 — Ktor 가 charset=UTF-8 을 자동으로 붙이는데
                    // NH 게이트웨이는 파라미터가 붙은 Content-Type 을 거부한다(IGW40050).
                    // TextContent 는 넘긴 ContentType 을 그대로 보낸다. 본문은 비운다.
                    setBody(TextContent("", ContentType.Application.FormUrlEncoded))
                }
            }.getOrElse { cause ->
                // cause 를 붙이지 않는다 — Ktor 타임아웃 메시지에는 appkey 가 담긴 URL 이 들어 있다
                throw IOException(cause::class.simpleName)
            }
        // 메시지는 그대로 화면에 나갈 수 있다 — 내부 라벨이 아니라 한국어로 쓴다.
        // NH 는 자격 오류에 401 이 아니라 403 을 주고, 본문에 정확한 사유를 담아 보낸다.
        // 그 사유를 버리면 사용자는 왜 막혔는지 알 길이 없다 — 있으면 그대로 쓴다.
        if (!response.status.isSuccess()) {
            val reason =
                loadResult { NhJson.decodeFromString<TokenErrorDto>(response.bodyAsText()).description }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
            val fallback =
                if (response.status.value < HTTP_SERVER_ERROR) {
                    "인증 실패 — 설정에서 앱 키를 확인하세요"
                } else {
                    "인증 서버 오류 — 잠시 후 다시 시도하세요"
                }
            throw NhException("HTTP${response.status.value}", reason ?: fallback)
        }
        return loadResult { NhJson.decodeFromString<TokenDto>(response.bodyAsText()) }
            .getOrElse { throw NhException("AUTH", "bad token body") }
    }

    /** 요청 간 최소 간격을 앱 전체에서 한 곳(여기)으로 지킨다. 슬롯은 [nowMs] 로 예약만 하고,
     *  실제 대기는 락 밖에서 한다 — 락 안에서 delay 하면 그동안 다른 요청이 슬롯조차 못 잡는다. */
    private suspend fun reserveSlot() {
        val waitMs =
            gateMutex.withLock {
                val now = nowMs()
                val floor = nextSlotAt?.plus(REQUEST_GAP_MS) ?: now // 첫 요청은 지킬 이전 슬롯이 없다
                val slot = maxOf(now, floor)
                nextSlotAt = slot
                slot - now
            }
        if (waitMs > 0) delay(waitMs)
    }

    private suspend fun call(
        path: String,
        body: JsonObject,
        cts: String?,
    ): HttpResponse {
        var bearer = token()
        var reissued = false
        var attempt = 0
        while (true) {
            reserveSlot()
            val response =
                client.post(REST + path) {
                    bearerAuth(bearer)
                    setBody(TextContent(body.toString(), ContentType.Application.Json))
                    if (cts != null) {
                        headers.append("cts", cts)
                        headers.append("cts_flag", "Y")
                    }
                }
            when {
                response.status == HttpStatusCode.Unauthorized && !reissued -> {
                    reissued = true
                    bearer = token(rejected = bearer)
                }

                response.status == HttpStatusCode.TooManyRequests && attempt < RATE_LIMIT_RETRIES -> {
                    delay(RATE_LIMIT_BASE_DELAY_MS shl attempt++)
                }

                else -> {
                    return response
                }
            }
        }
    }

    /**
     * 연속조회. `cts`/`cts_flag` 는 응답 **헤더**로 오고 다음 요청 헤더로 되돌려 보낸다.
     *
     * [enough] 이 true 를 내면 cts 가 남아 있어도 그 자리에서 멈춘다 — 서버가 요청 건수(array_cnt)를
     * 무시하고 몇 페이지고 더 줄 수 있는 API(quote/v1/period 가 그렇다) 에서, 호출부가 이미 필요한
     * 만큼 받았으면 남은 수십 페이지를 마저 받았다가 버리는 낭비를 막는다. 기본값은 끝까지 받는다(false).
     */
    @Suppress("ThrowsCount")
    private suspend inline fun <reified A, reified B> pages(
        path: String,
        input: JsonObject,
        enough: (List<NhResponse<A, B>>) -> Boolean = { false },
    ): List<NhResponse<A, B>> {
        val out = mutableListOf<NhResponse<A, B>>()
        var cts: String? = null
        while (true) {
            val response = call(path, input, cts)
            if (!response.status.isSuccess()) {
                throw NhException("HTTP${response.status.value}", "요청이 거부되었습니다")
            }
            val parsed =
                loadResult { NhJson.decodeFromString<NhResponse<A, B>>(response.bodyAsText()) }
                    .getOrElse { throw NhException("HTTP${response.status.value}", "응답 형식 오류") }
            // 유일한 네트워크 로그. release 에서는 R8 이 제거한다.
            Log.d("NhApi", "$path rsp_cd=${parsed.rspCd} ${parsed.rspMsg}")
            if (parsed.output0 == null && parsed.output1 == null && !parsed.ok) {
                throw NhException(parsed.rspCd, parsed.rspMsg)
            }
            out += parsed
            if (enough(out)) return out
            val next = response.headers["cts"]
            if (response.headers["cts_flag"] != "Y" || next.isNullOrEmpty() || next == cts) return out
            cts = next
        }
    }

    /**
     * 사용자 범위의 실시간 체결통보. **토큰의 함수**라서 토큰이 바뀌면 자동으로 재구독하고,
     * 잠기면([Vault.lock]) `secretsFlow` 가 빈 [Secrets] 를 내보내 세션이 구조적으로 취소된다 —
     * 타이밍 상수에 기대는 부분이 없다.
     *
     * 수집자가 하나면 세션도 하나다(NH 는 앱키당 2세션). 화면을 떠나면 취소가 소켓을 닫는다.
     *
     * 이 소켓은 REST 가 확보한 토큰을 **따라간다** — 스스로 토큰을 발급하지 않는다.
     * 캐시된 토큰이 없으면 연결 자체를 시도하지 않는다. `PortfolioViewModel` 은 balance() 호출과
     * 이 구독을 같은 merge/combine 으로 동시에 시작해 REST 가 먼저 끝난다는 보장은 없지만,
     * balance() 가 토큰을 채우는 순간 `secretsFlow` 가 그 값을 흘려보내 소켓이 스스로 뒤따라 붙는다.
     * 자격증명만 있고 발급이 실패하는 상태에서 소켓이 발급을 재시도하면
     * 30초마다 토큰 발급을 두드리게 되어 NH 보안 알림을 유발한다 — 그래서 따라가기만 한다.
     */
    fun fills(): Flow<Fill> = fillsFrom(WS)

    internal fun fillsFrom(ws: String): Flow<Fill> {
        var streak = 0
        return vault.secretsFlow
            .map { it.token }
            .distinctUntilChanged()
            .flatMapLatest { cached ->
                if (cached == null) {
                    emptyFlow()
                } else {
                    flow {
                        val bearer = token() // 만료됐으면 여기서 발급된다
                        val session = client.webSocketSession(ws)
                        val openedAt = System.nanoTime()
                        try {
                            CHANNELS.forEach { session.send(Frame.Text(subscribeFrame(bearer, it))) }
                            for (frame in session.incoming) {
                                // streak = 0 은 여기서 하지 않는다 — NH 는 구독 ack 를 먼저 보내므로
                                // ack 만 받고 끊기는 세션이 '건강한 연결' 로 위장해 백오프를 무력화한다.
                                if (frame is Frame.Text) parseFill(frame.readText())?.let { emit(it) }
                            }
                        } finally {
                            // 백오프 상한보다 오래 살아남은 세션만 건강했다고 본다.
                            if ((System.nanoTime() - openedAt) / 1_000_000 > BACKOFF_MAX_MS) streak = 0
                            session.cancel() // close() 는 suspend 라 취소된 컨텍스트에서 못 쓴다
                        }
                        throw NhException("WS", "closed") // 정상 Close 도 재연결 대상이다
                    }
                }
            }.retryWhen { _, _ ->
                delay(backoffMs(streak++) + Random.nextLong(BACKOFF_JITTER_MS))
                true
            }
    }

    internal companion object {
        /** 해외 체결통보 `d0` 를 붙일 때는 여기 한 줄. 통보 채널은 전부 같은 7070 세션이다. */
        internal val CHANNELS = listOf("d2")

        /**
         * 정확히
         * `{"header":{"token":"<token>","tr_type":"1"},"body":{"tr_cd":"<trCd>","tr_key":""}}`.
         * `tr_key` 는 빈 문자열로 **존재해야** 하고 `tr_type` 은 문자열 `"1"` 이다.
         */
        internal fun subscribeFrame(
            token: String,
            trCd: String,
        ): String =
            buildJsonObject {
                put(
                    "header",
                    buildJsonObject {
                        put("token", token)
                        put("tr_type", "1")
                    },
                )
                put(
                    "body",
                    buildJsonObject {
                        put("tr_cd", trCd)
                        put("tr_key", "")
                    },
                )
            }.toString()

        /** 체결 프레임만 [Fill] 로. ack·다른 채널·깨진 값은 null 이고 연결은 유지된다. */
        internal fun parseFill(text: String): Fill? =
            runCatching {
                val root = NhJson.parseToJsonElement(text).jsonObject
                val trCd =
                    root["header"]
                        ?.jsonObject
                        ?.get("tr_cd")
                        ?.jsonPrimitive
                        ?.content
                if (trCd == null || trCd !in CHANNELS) return null
                val body = root["body"] ?: return null
                NhJson.decodeFromJsonElement<FillDto>(body).toFill()
            }.getOrNull()

        internal fun backoffMs(streak: Int): Long = minOf(BACKOFF_MAX_MS, BACKOFF_BASE_MS shl minOf(streak, BACKOFF_MAX_SHIFT))
    }
}

@Serializable
private data class AccountDto(
    @SerialName("acct_no") val no: String,
    @SerialName("acct_type") val type: String,
)

@Serializable
private data class BalanceSummaryDto(
    /** D+2 예수금 — 당일 체결이 즉시 반영된다 (dca 는 D+0 이라 이틀간 움직이지 않는다). */
    @SerialName("nxt2_dd_dca") val cash: Long = 0,
)

@Serializable
private data class HoldingDto(
    @SerialName("iem_cd") val code: String,
    @SerialName("iem_nm") val name: String = "",
    @SerialName("itg_bnc_qty") val qty: Double = 0.0,
    @SerialName("rsdl_qty") val remainQty: Double = 0.0,
    @SerialName("phs_pr") val avgPrice: Long = 0,
    @SerialName("now_pr") val price: Long = 0,
    @SerialName("eal_amt") val evalAmt: Long = 0,
    @SerialName("pft_rt") val pnlRate: Double = 0.0,
    // 신용/융자 구분. 상품유형명은 배지 문구로 그대로 쓰고, 대출 정보로 신용 여부를 판정한다.
    @SerialName("pdt_tp_nm") val productType: String = "",
    // NH 는 대출이 없는 일반 매수분에서 이 필드를 빈 문자열로 내려준다 — Long 이면 "" 를
    // 못 읽어 잔고 조회 전체가 죽는다(coerceInputValues/isLenient 둘 다 빈 문자열은 못 구한다).
    @SerialName("lon_bnc_amt") val loanAmt: String = "",
    @SerialName("lon_byn_dt") val loanDate: String = "",
    // 상품유형명(pdt_tp_nm)이 실기기에서 비어 있어 현금분·신용분을 못 가른다 — typeName
    // (유형코드명)이 실제로 신원을 가른다([Holding.key] 참고). 통합잔고유형코드(itg_bnc_tp_cd)는
    // 실기기에서 늘 빈 값이라 신원·표시 어느 쪽에도 못 써 필드째 제거했다.
    @SerialName("tp_cd_nm") val typeName: String = "",
) {
    fun toHolding() =
        Holding(
            code = code,
            name = name,
            qty = qty.toLong(),
            remainQty = remainQty.toLong(),
            avgPrice = avgPrice,
            price = price,
            evalAmt = evalAmt,
            pnlRate = pnlRate,
            productType = productType.trim(),
            loanAmt = loanAmt.toLongOrNull() ?: 0,
            loanDate = loanDate,
            typeName = typeName.trim(),
        )
}

@Serializable
private data class TokenDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long = 0,
) {
    override fun toString(): String = "TokenDto(***)"
}

/**
 * 토큰 발급 실패 응답. NH 는 자격 오류에 **403** 과 한국어 설명을 준다 —
 * 예: `{"error_description":"유효하지 않은 AppKey입니다.","error_code":"IGW40031"}`.
 * 이 문구가 우리가 만들 수 있는 어떤 문장보다 정확해서 그대로 화면에 낸다.
 */
@Serializable
private data class TokenErrorDto(
    @SerialName("error_description") val description: String = "",
)

@Serializable
private data class FillDto(
    // 실제 체결 프레임이 반드시 갖는 세 필드에는 기본값을 두지 않는다 —
    // 그래야 ack·오류 프레임이 디코드에 실패해 null 이 된다.
    val accountno: String,
    val concgty: String,
    val concprc: String,
    @SerialName("issue_nm") val name: String = "",
    val conctime: String = "",
) {
    fun toFill(): Fill? =
        Fill(
            acctNo = accountno,
            name = name,
            qty = concgty.trim().toLongOrNull() ?: return null, // "0000000005" -> 5
            price = concprc.trim().toLongOrNull() ?: return null,
            time = conctime,
        )
}

/** ETF 구성종목 한 줄. */
@Serializable
private data class ComponentDto(
    @SerialName("iem_cd") val code: String = "",
)

private const val ISIN_KR_PREFIX = "KR7"
private const val ISIN_LENGTH = 12
private val SHORT_CODE_REGEX = Regex("^\\d{6}$")

/** ISIN(KR7 로 시작하는 12자)이면 6자 단축코드로 바꾸고, 그 결과가 6자리 숫자가 아니면 버린다. */
private fun shortCode(raw: String): String? {
    val trimmed = raw.trim()
    val code = if (trimmed.length == ISIN_LENGTH && trimmed.startsWith(ISIN_KR_PREFIX)) trimmed.substring(3, 9) else trimmed
    return code.takeIf { SHORT_CODE_REGEX.matches(it) }
}

/** 액면분할·병합·주식분할·병합 */
private val FCAM_EX_RIGHT_CODES = setOf("01", "02", "03", "04")

/** 권리락·권배락·권리중간배당락·권리분기배당락 */
private val FLNG_EX_RIGHT_CODES = setOf("01", "04", "06", "07")

/** [date](YYYYMMDD, 8자리 숫자로 검증된 값만 들어온다) 의 전날. dailyBars 의 수동 페이징 전용. */
private fun dayBefore(date: String): String =
    LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE).minusDays(1).format(DateTimeFormatter.BASIC_ISO_DATE)

private val DATE_REGEX = Regex("^\\d{8}$")

/** [DATE_REGEX] 형식(8자리 숫자)이면서 실제 달력에 있는 날짜인지 — 13월처럼 자릿수만 맞는 값을 걸러낸다.
 *  market/MarketData.kt 에도 같은 이름의 함수가 있지만 코드는 공유하지 않는다(파일별로 닫아 둔다). */
private fun String.isCalendarDate(): Boolean =
    DATE_REGEX.matches(this) && runCatching { LocalDate.parse(this, DateTimeFormatter.BASIC_ISO_DATE) }.isSuccess

/** 일봉 한 줄. [Bar.exRight] 판정을 이 파일 안에서 끝낸다 — NH 코드값을 아는 곳은 여기뿐이다. */
@Serializable
private data class BarDto(
    @SerialName("bsop_date") val date: String = "",
    @SerialName("stck_prpr") val close: String = "",
    @SerialName("stck_sdpr") val refPrice: String = "",
    @SerialName("flng_cls_code") val flngCode: String = "",
    @SerialName("fcam_mod_cls_code") val fcamCode: String = "",
) {
    /** 일자가 8자리 숫자가 아니거나(또는 13월처럼 자릿수만 맞을 뿐 달력에 없거나) 종가가 양의
     *  정수가 아니면 그 날은 버린다(dayBefore() 가 빈 문자열이나 무효한 날짜를 파싱하다 죽는
     *  일을 막는다). 기준가가 무효면 0 으로 두고 exRight 도 강제로 false 다 — 0 인 기준가가
     *  수정주가 보정([dev.nhportfolio.market.Breadth.adjust])으로 새면 안 된다. */
    fun toBar(): Bar? {
        val dateValue = date.trim().takeIf { it.isCalendarDate() } ?: return null
        val closeValue = close.trim().toIntOrNull()?.takeIf { it > 0 } ?: return null
        val refValue = refPrice.trim().toIntOrNull()?.takeIf { it > 0 }
        return Bar(
            date = dateValue,
            close = closeValue,
            refPrice = refValue ?: 0,
            exRight = refValue != null && (fcamCode.trim() in FCAM_EX_RIGHT_CODES || flngCode.trim() in FLNG_EX_RIGHT_CODES),
        )
    }
}
