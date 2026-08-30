package dev.nhportfolio.api

import android.util.Log
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
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
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
private const val CONNECT_TIMEOUT_MS = 10_000L

private const val WS = "wss://api.nhplug.com:7070/websocket" // 통보 채널은 국내·해외 모두 7070
private const val BACKOFF_JITTER_MS = 500L
private const val BACKOFF_BASE_MS = 1_000L
private const val BACKOFF_MAX_MS = 30_000L
private const val BACKOFF_MAX_SHIFT = 5

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
                    setBody(FormDataContent(Parameters.Empty)) // Content-Type 만 x-www-form-urlencoded, 본문은 비움
                }
            }.getOrElse { cause ->
                // cause 를 붙이지 않는다 — Ktor 타임아웃 메시지에는 appkey 가 담긴 URL 이 들어 있다
                throw IOException(cause::class.simpleName)
            }
        // 메시지는 그대로 화면에 나갈 수 있다 — 내부 라벨이 아니라 한국어로 쓴다
        if (!response.status.isSuccess()) {
            throw NhException("HTTP${response.status.value}", "인증 서버 오류 — 잠시 후 다시 시도하세요")
        }
        return loadResult { NhJson.decodeFromString<TokenDto>(response.bodyAsText()) }
            .getOrElse { throw NhException("AUTH", "bad token body") }
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

    /** 연속조회. `cts`/`cts_flag` 는 응답 **헤더**로 오고 다음 요청 헤더로 되돌려 보낸다. */
    @Suppress("ThrowsCount")
    private suspend inline fun <reified A, reified B> pages(
        path: String,
        input: JsonObject,
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
) {
    fun toHolding() = Holding(code, name, qty.toLong(), remainQty.toLong(), avgPrice, price, evalAmt, pnlRate)
}

@Serializable
private data class TokenDto(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_in") val expiresIn: Long = 0,
) {
    override fun toString(): String = "TokenDto(***)"
}

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
