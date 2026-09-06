package dev.nhportfolio

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import dev.nhportfolio.api.NhApi
import dev.nhportfolio.api.NhException
import dev.nhportfolio.api.loadResult
import dev.nhportfolio.model.Account
import dev.nhportfolio.security.Vault
import dev.nhportfolio.ui.userMessage
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HOUR = 3_600_000L
private val FMT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

private fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    extra: Headers = Headers.Empty,
) = respond(
    content = body,
    status = status,
    headers =
        Headers.build {
            appendAll(extra)
            append(HttpHeaders.ContentType, "application/json")
        },
)

private const val TOKEN_BODY = """{"access_token":"T1","token_type":"Bearer","expires_in":86400}"""

/** NH 가 만료·무효 토큰에 실제로 돌려주는 모양 — 실기기 확인(2026-09-06), HTTP 상태는 400 이다. */
private const val INVALID_TOKEN_BODY = """{"rsp_cd":"IGW40043","rsp_msg":"유효하지 않은 token 입니다."}"""

private const val ACCOUNTS_BODY = """
{"rsp_cd":"00000","rsp_msg":"조회가 완료되었습니다.","cust_no":"1",
 "Output_0":[{"acct_no":"20101036881","acct_type":"01"},{"acct_no":"50051036881","acct_type":"03"}]}
"""

private const val BALANCE_BODY = """
{"rsp_cd":"00000","rsp_msg":"조회가 완료되었습니다.",
 "Output_0":{"dca":111,"nxt2_dd_dca":500000,"tot_eal_amt":700000},
 "Output_1":[{"iem_cd":"005930","iem_nm":"삼성전자","itg_bnc_qty":10.0,"rsdl_qty":10.0,
              "phs_pr":68000,"now_pr":70000,"eal_amt":700000,"pft_rt":2.94}]}
"""

/**
 * 같은 종목코드가 신용/융자 매수분으로 온 경우 — pdt_tp_nm·lon_bnc_amt·lon_byn_dt 에 더해
 * 신원을 가르는 tp_cd_nm 도 실려 있다. 둘 다 NH 고정폭 필드라 뒤 공백이 섞여 올 수 있어
 * 여기서부터 채워 넣어 트리밍까지 함께 확인한다.
 */
private const val BALANCE_CREDIT_BODY = """
{"rsp_cd":"00000","rsp_msg":"조회가 완료되었습니다.",
 "Output_0":{"dca":111,"nxt2_dd_dca":500000,"tot_eal_amt":350000},
 "Output_1":[{"iem_cd":"005930","iem_nm":"삼성전자","itg_bnc_qty":5.0,"rsdl_qty":5.0,
              "phs_pr":68000,"now_pr":70000,"eal_amt":350000,"pft_rt":2.94,
              "pdt_tp_nm":"신용융자  ","lon_bnc_amt":1000000,"lon_byn_dt":"20260115",
              "tp_cd_nm":"신용융자  "}]}
"""

/** 대출이 없는 일반(위탁) 매수분 — NH 는 lon_bnc_amt 를 숫자 0 이 아니라 빈 문자열로 내려준다. */
private const val BALANCE_BLANK_LOAN_AMT_BODY = """
{"rsp_cd":"00000","rsp_msg":"조회가 완료되었습니다.",
 "Output_0":{"dca":111,"nxt2_dd_dca":500000,"tot_eal_amt":700000},
 "Output_1":[{"iem_cd":"005930","iem_nm":"삼성전자","itg_bnc_qty":10.0,"rsdl_qty":10.0,
              "phs_pr":68000,"now_pr":70000,"eal_amt":700000,"pft_rt":2.94,
              "pdt_tp_nm":"위탁","lon_bnc_amt":"","lon_byn_dt":""}]}
"""

private const val ETF_COMPONENTS_BODY = """
{"rsp_cd":"00000","rsp_msg":"완료",
 "Output_0":[{"iem_cd":"005930"},{"iem_cd":"000660"}]}
"""

/** 공백 채움 6자리, ISIN(KR7...), 빈 문자열, 중복이 섞여 온다. */
private const val ETF_COMPONENTS_MIXED_CODES_BODY = """
{"rsp_cd":"00000","rsp_msg":"완료",
 "Output_0":[{"iem_cd":" 005930 "},{"iem_cd":"KR7005930003"},{"iem_cd":""},
             {"iem_cd":"000660"},{"iem_cd":"000660"}]}
"""

/** 일자 순서가 뒤섞여 오고, 한 필드는 앞뒤 공백까지 채워져 있다("  71000"). */
private const val DAILY_BARS_BODY = """
{"rsp_cd":"00000","rsp_msg":"완료","Output_0":{},
 "Output_1":[{"bsop_date":"20260103","stck_prpr":" 71000","stck_sdpr":"70000"},
             {"bsop_date":"20260102","stck_prpr":"70000","stck_sdpr":"69000"},
             {"bsop_date":"20260104","stck_prpr":"72000","stck_sdpr":"71000"}]}
"""

/** DAILY_BARS_BODY 와 같은 세 날짜를 최신순으로 되돌린다 — 정렬이 순서에 기대면 안 된다. */
private const val DAILY_BARS_DESC_BODY = """
{"rsp_cd":"00000","rsp_msg":"완료",
 "Output_1":[{"bsop_date":"20260104","stck_prpr":"72000","stck_sdpr":"71000"},
             {"bsop_date":"20260103","stck_prpr":"71000","stck_sdpr":"70000"},
             {"bsop_date":"20260102","stck_prpr":"70000","stck_sdpr":"69000"}]}
"""

/** 20260103 은 stck_prpr 가 공백 채움이라 버려져야 한다. */
private const val DAILY_BARS_BAD_CLOSE_BODY = """
{"rsp_cd":"00000","rsp_msg":"완료",
 "Output_1":[{"bsop_date":"20260102","stck_prpr":"70000","stck_sdpr":"69000"},
             {"bsop_date":"20260103","stck_prpr":"   ","stck_sdpr":"70000"}]}
"""

/** stck_sdpr 가 빈 문자열이면 fcam_mod_cls_code 가 있어도 exRight 는 false 여야 한다. */
private const val DAILY_BARS_BAD_REF_BODY = """
{"rsp_cd":"00000","rsp_msg":"완료",
 "Output_1":[{"bsop_date":"20260102","stck_prpr":"70000","stck_sdpr":"",
              "fcam_mod_cls_code":"01"}]}
"""

private const val DAILY_BARS_EX_RIGHT_BODY = """
{"rsp_cd":"00000","rsp_msg":"완료",
 "Output_1":[{"bsop_date":"20260102","stck_prpr":"70000","stck_sdpr":"69000",
              "fcam_mod_cls_code":"01"}]}
"""

/** flng_cls_code=02 는 배당락이다 — 권리락(수정주가 대상)이 아니다. */
private const val DAILY_BARS_DIVIDEND_BODY = """
{"rsp_cd":"00000","rsp_msg":"완료",
 "Output_1":[{"bsop_date":"20260102","stck_prpr":"70000","stck_sdpr":"69000",
              "flng_cls_code":"02"}]}
"""

/** dailyBars 의 수동 페이징 가드용 — 매 호출 같은 두 날짜만 되돌려 진전이 없다. */
private const val DAILY_BARS_SAME_OLDEST_BODY = """
{"rsp_cd":"00000","rsp_msg":"완료",
 "Output_1":[{"bsop_date":"20260102","stck_prpr":"102","stck_sdpr":"102"},
             {"bsop_date":"20260101","stck_prpr":"101","stck_sdpr":"101"}]}
"""

/** cts 없이 2건씩 잘려 오는 /period 응답의 첫/둘째/셋째 페이지 — edate 로만 이어 받는다. */
private const val PERIOD_PAGE_1_BODY = """
{"rsp_cd":"00000","rsp_msg":"완료",
 "Output_1":[{"bsop_date":"20260110","stck_prpr":"110","stck_sdpr":"110"},
             {"bsop_date":"20260109","stck_prpr":"109","stck_sdpr":"109"}]}
"""

private const val PERIOD_PAGE_2_BODY = """
{"rsp_cd":"00000","rsp_msg":"완료",
 "Output_1":[{"bsop_date":"20260108","stck_prpr":"108","stck_sdpr":"108"},
             {"bsop_date":"20260107","stck_prpr":"107","stck_sdpr":"107"}]}
"""

private const val PERIOD_PAGE_3_BODY = """
{"rsp_cd":"00000","rsp_msg":"완료",
 "Output_1":[{"bsop_date":"20260106","stck_prpr":"106","stck_sdpr":"106"},
             {"bsop_date":"20260105","stck_prpr":"105","stck_sdpr":"105"}]}
"""

/** 한 행은 bsop_date 가 빈 문자열이고, 다른 한 행은 앞에 공백이 있지만 트림하면 유효하다. */
private const val DAILY_BARS_BLANK_DATE_BODY = """
{"rsp_cd":"00000","rsp_msg":"완료",
 "Output_1":[{"bsop_date":"","stck_prpr":"999","stck_sdpr":"999"},
             {"bsop_date":" 20260102","stck_prpr":"70000","stck_sdpr":"69000"}]}
"""

/** 한 행의 bsop_date 는 8자리 숫자이지만 13월이라 달력에 없다 — 빈 값과 같이 버려져야 한다. */
private const val DAILY_BARS_INVALID_CALENDAR_DATE_BODY = """
{"rsp_cd":"00000","rsp_msg":"완료",
 "Output_1":[{"bsop_date":"20261332","stck_prpr":"999","stck_sdpr":"999"},
             {"bsop_date":"20260102","stck_prpr":"70000","stck_sdpr":"69000"}]}
"""

/** 서버가 edate 를 무시하고 oldest 를 역행시킨다(1페이지 20260108 → 2페이지 20260109) — 가드 테스트용. */
private const val PERIOD_REGRESS_A_BODY = """
{"rsp_cd":"00000","rsp_msg":"완료",
 "Output_1":[{"bsop_date":"20260110","stck_prpr":"110","stck_sdpr":"110"},
             {"bsop_date":"20260108","stck_prpr":"108","stck_sdpr":"108"}]}
"""

private const val PERIOD_REGRESS_B_BODY = """
{"rsp_cd":"00000","rsp_msg":"완료",
 "Output_1":[{"bsop_date":"20260112","stck_prpr":"112","stck_sdpr":"112"},
             {"bsop_date":"20260109","stck_prpr":"109","stck_sdpr":"109"}]}
"""

private class ApiFixture(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val dir: File = Files.createTempDirectory("api").toFile()
    private val macKey = SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256")

    val store =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ) { File(dir, "api.preferences_pb") }

    val vault =
        Vault(
            store = store,
            hmac = { data, _ -> Mac.getInstance("HmacSHA256").apply { init(macKey) }.doFinal(data) },
            elapsed = { 0L },
            bootCount = { 1 },
        )

    val requests: MutableList<HttpRequestData> = CopyOnWriteArrayList()
    var handle: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData = { json("{}") }

    val api =
        NhApi(
            vault,
            MockEngine { request ->
                requests += request
                handle(request)
            },
            nowMs,
        )

    val tokenCalls get() = requests.count { it.url.encodedPath == "/oauth2/token" }

    suspend fun ready() {
        vault.setPin("135790".toCharArray())
        vault.update { it.copy(appKey = "APPKEY", appSecret = "APPSECRET") }
    }

    suspend fun seedToken(
        token: String,
        expiresAt: Long,
        issuedAt: Long,
    ) {
        vault.update { it.copy(token = token, tokenExpiresAt = expiresAt, tokenIssuedAt = issuedAt) }
    }
}

@Suppress("LargeClass") // NhApi 표면이 늘수록 자연히 커진다 — 쪼갤 하위 도메인이 없다
class NhApiTest {
    @Test
    fun `콜드 스타트는 토큰을 정확히 한 번 발급한다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(ACCOUNTS_BODY) }

            f.api.accounts()
            f.api.accounts()

            assertEquals(1, f.tokenCalls)
            val token = f.requests.first { it.url.encodedPath == "/oauth2/token" }
            assertEquals("POST", token.method.value)
            assertEquals("APPKEY", token.url.parameters["appkey"])
            assertEquals("APPSECRET", token.url.parameters["appsecretkey"])
            assertEquals("client_credentials", token.url.parameters["grant_type"])
            assertEquals("oob", token.url.parameters["scope"])
            assertNull(token.headers[HttpHeaders.Authorization])
            // startsWith 로 두면 안 된다 — NH 게이트웨이는 charset 같은 파라미터가 붙은
            // Content-Type 을 거부한다(IGW40050 "Content-Type이 유효하지 않습니다").
            // 정확히 일치해야 한다. 느슨하게 봤다가 실기기에서 발급이 통째로 막혔다.
            assertEquals(
                "application/x-www-form-urlencoded",
                token.body.contentType.toString(),
            )
        }

    @Test
    fun `토큰 발급이 5xx 로 실패해도 화면에는 한국어가 나간다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { json("""{"error":"boom"}""", HttpStatusCode.InternalServerError) }

            val e = assertFailsWith<NhException> { f.api.accounts() }

            // 요청이 하나뿐이어야 토큰 발급 지점(throw)에서 막힌 것이 보장된다 —
            // 안 그러면 도메인 호출 경로의 HTTP500 도 이 assert 를 통과해 버린다.
            assertEquals(1, f.requests.size)
            // userMessage() 는 매핑되지 않은 코드에서 message 를 그대로 내보낸다(NH 의 한국어
            // rsp_msg 를 살리기 위한 의도된 동작). 그러니 던지는 쪽이 내부 라벨이 아니라
            // 한국어를 담아야 한다 — 안 그러면 사용자가 "token" 같은 영어를 보게 된다.
            assertEquals("HTTP500", e.code)
            assertTrue(
                e.userMessage().none { it in 'a'..'z' || it in 'A'..'Z' },
                "화면에 영어 내부 라벨이 나갔다: ${e.userMessage()}",
            )
        }

    @Test
    fun `토큰 403 은 NH 가 보낸 사유를 그대로 보여준다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            // 실제 NH 응답 형태다 — 자격 오류에 401 이 아니라 403 을 준다.
            f.handle = {
                json(
                    """{"error_description":"유효하지 않은 AppKey입니다.","error_code":"IGW40031"}""",
                    HttpStatusCode.Forbidden,
                )
            }

            val e = assertFailsWith<NhException> { f.api.accounts() }

            assertEquals("HTTP403", e.code)
            // 403 은 Format 의 코드 매핑에 없어서 message 가 그대로 화면에 나간다.
            // "인증 서버 오류 — 잠시 후..." 가 나오면 서버 장애로 오인시키는 버그다.
            assertEquals("유효하지 않은 AppKey입니다.", e.userMessage())
        }

    @Test
    fun `토큰 4xx 에 본문이 없으면 앱 키를 확인하라고 안내한다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { json("", HttpStatusCode.Forbidden) }

            val e = assertFailsWith<NhException> { f.api.accounts() }

            assertEquals("인증 실패 — 설정에서 앱 키를 확인하세요", e.userMessage())
        }

    @Test
    fun `유효한 저장 토큰이 있으면 발급하지 않는다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.seedToken("T0", expiresAt = Long.MAX_VALUE, issuedAt = System.currentTimeMillis())
            f.handle = { json(ACCOUNTS_BODY) }

            f.api.accounts()
            assertEquals(0, f.tokenCalls)
        }

    @Test
    fun `만료된 토큰은 재발급하고 만료 시각을 저장한다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.seedToken("T0", expiresAt = 1, issuedAt = 1)
            f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(ACCOUNTS_BODY) }

            val before = System.currentTimeMillis()
            f.api.accounts()
            assertEquals(1, f.tokenCalls)

            val secrets = f.vault.secrets()
            assertEquals("T1", secrets.token)
            val expected = before + 86_400_000L - 60_000L
            assertTrue(secrets.tokenExpiresAt in expected..(expected + 5_000), "expiresAt=${secrets.tokenExpiresAt}")
        }

    @Test
    fun `운영 계좌만 남기고 모의계좌는 제외한다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(ACCOUNTS_BODY) }

            assertEquals(listOf(Account("20101036881")), f.api.accounts())
        }

    @Test
    fun `계좌 목록은 모든 페이지를 합산한다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            var page = 0
            f.handle = { req ->
                when {
                    req.url.encodedPath == "/oauth2/token" -> {
                        json(TOKEN_BODY)
                    }

                    page++ == 0 -> {
                        json(
                            """{"rsp_cd":"00000","rsp_msg":"완료","Output_0":[{"acct_no":"1","acct_type":"01"}]}""",
                            extra = headersOf("cts" to listOf("C1"), "cts_flag" to listOf("Y")),
                        )
                    }

                    else -> {
                        json("""{"rsp_cd":"00000","rsp_msg":"완료","Output_0":[{"acct_no":"2","acct_type":"02"}]}""")
                    }
                }
            }

            assertEquals(listOf(Account("1"), Account("2")), f.api.accounts())
            val second = f.requests.last()
            assertEquals("C1", second.headers["cts"])
            assertEquals("Y", second.headers["cts_flag"])
        }

    @Test
    fun `cts 가 반복되면 연속조회를 멈춘다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") {
                    json(TOKEN_BODY)
                } else {
                    json(
                        """{"rsp_cd":"00000","rsp_msg":"완료","Output_0":[{"acct_no":"1","acct_type":"01"}]}""",
                        extra = headersOf("cts" to listOf("SAME"), "cts_flag" to listOf("Y")),
                    )
                }
            }

            f.api.accounts()
            assertEquals(2, f.requests.count { it.url.encodedPath == "/n2/acctinfo" })
        }

    @Test
    fun `잔고는 D+2 예수금과 보유 종목을 돌려준다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(BALANCE_BODY) }

            val balance = f.api.balance(Account("20101036881"))
            assertEquals(500_000, balance.cash)
            val h = balance.holdings.single()
            assertEquals("005930", h.code)
            assertEquals("삼성전자", h.name)
            assertEquals(10, h.qty)
            assertEquals(68_000, h.avgPrice)
            assertEquals(70_000, h.price)
            assertEquals(700_000, h.evalAmt)

            val body = (f.requests.last { it.url.encodedPath.endsWith("/balance") }.body as TextContent).text
            assertTrue("\"act_no\":\"20101036881\"" in body, body)
            assertTrue("\"qut_dit_cd\":\"UNT\"" in body, body)
            assertTrue("\"bnc_bse_cd\":\"1\"" in body, body)
            assertTrue("\"ltg_aot_dit_cd\":\"1\"" in body, body)
            assertTrue("\"aet_bse\":\"1\"" in body, body)
        }

    @Test
    fun `신용융자 필드가 있으면 상품유형과 대출 정보가 실리고 신용으로 판정한다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(BALANCE_CREDIT_BODY) }

            val balance = f.api.balance(Account("20101036881"))
            val h = balance.holdings.single()

            assertEquals("신용융자", h.productType)
            assertEquals(1_000_000, h.loanAmt)
            assertEquals("20260115", h.loanDate)
            assertTrue(h.onCredit)
            assertEquals("신용융자", h.typeName)
        }

    @Test
    fun `신용 필드가 통째로 없어도 파싱은 성공하고 신용이 아니다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(BALANCE_BODY) }

            val balance = f.api.balance(Account("20101036881"))
            val h = balance.holdings.single()

            assertEquals("", h.productType)
            assertFalse(h.onCredit)
            assertEquals("", h.typeName)
        }

    @Test
    fun `lon_bnc_amt 가 빈 문자열이어도 파싱은 성공하고 대출잔고는 0이다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(BALANCE_BLANK_LOAN_AMT_BODY)
            }

            val balance = f.api.balance(Account("20101036881"))
            val h = balance.holdings.single()

            assertEquals(0, h.loanAmt)
            assertFalse(h.onCredit)
        }

    @Test
    fun `보유 블록이 없어도 오류가 아니다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") {
                    json(TOKEN_BODY)
                } else {
                    json("""{"rsp_cd":"00000","rsp_msg":"조회가 완료되었습니다.","Output_0":{"nxt2_dd_dca":1000}}""")
                }
            }

            val balance = f.api.balance(Account("1"))
            assertEquals(1_000, balance.cash)
            assertTrue(balance.holdings.isEmpty())
        }

    @Test
    fun `완료를 부정하는 메시지는 성공으로 보지 않는다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") {
                    json(TOKEN_BODY)
                } else {
                    json("""{"rsp_cd":"90001","rsp_msg":"조회가 완료되지 않았습니다"}""")
                }
            }

            val e = assertFailsWith<NhException> { f.api.balance(Account("20101036881")) }
            assertEquals("90001", e.code)
            assertEquals("조회가 완료되지 않았습니다", e.message)
        }

    @Test
    fun `블록이 없어도 완료 메시지면 빈 결과다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") {
                    json(TOKEN_BODY)
                } else {
                    json("""{"rsp_cd":"99999","rsp_msg":"정상처리 완료"}""")
                }
            }

            assertTrue(f.api.accounts().isEmpty())
        }

    @Test
    fun `블록도 없고 완료도 아니면 업무 오류다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") {
                    json(TOKEN_BODY)
                } else {
                    json("""{"rsp_cd":"40010","rsp_msg":"종목코드 항목을 입력하세요."}""")
                }
            }

            val e = assertFailsWith<NhException> { f.api.accounts() }
            assertEquals("40010", e.code)
            assertEquals("종목코드 항목을 입력하세요.", e.message)
        }

    @Test
    fun `두 번째 페이지의 오류도 부분 결과로 넘기지 않는다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            var page = 0
            f.handle = { req ->
                when {
                    req.url.encodedPath == "/oauth2/token" -> {
                        json(TOKEN_BODY)
                    }

                    page++ == 0 -> {
                        json(
                            """{"rsp_cd":"00000","rsp_msg":"완료","Output_0":[{"acct_no":"1","acct_type":"01"}]}""",
                            extra = headersOf("cts" to listOf("C1"), "cts_flag" to listOf("Y")),
                        )
                    }

                    else -> {
                        json("""{"rsp_cd":"40010","rsp_msg":"조회 실패"}""")
                    }
                }
            }

            assertFailsWith<NhException> { f.api.accounts() }
        }

    @Test
    fun `401 이면 한 번 재발급하고 한 번 재시도한다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.seedToken("STALE", expiresAt = Long.MAX_VALUE, issuedAt = System.currentTimeMillis() - 2 * HOUR)
            f.handle = { req ->
                when {
                    req.url.encodedPath == "/oauth2/token" -> json(TOKEN_BODY)
                    req.headers[HttpHeaders.Authorization] == "Bearer STALE" -> json("{}", HttpStatusCode.Unauthorized)
                    else -> json(ACCOUNTS_BODY)
                }
            }

            assertEquals(1, f.api.accounts().size)
            assertEquals(1, f.tokenCalls)
            assertEquals("T1", f.vault.secrets().token)
        }

    @Test
    fun `동시에 401 을 만나도 발급은 한 번이다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.seedToken("STALE", expiresAt = Long.MAX_VALUE, issuedAt = System.currentTimeMillis() - 2 * HOUR)
            val staleSeen = CompletableDeferred<Unit>()
            val staleCount = AtomicInteger(0)
            f.handle = { req ->
                when {
                    req.url.encodedPath == "/oauth2/token" -> {
                        json(TOKEN_BODY)
                    }

                    req.headers[HttpHeaders.Authorization] == "Bearer STALE" -> {
                        if (staleCount.incrementAndGet() >= 2) staleSeen.complete(Unit)
                        staleSeen.await() // 두 코루틴이 모두 STALE 로 요청할 때까지 대기 — 직렬화되면 이 테스트는 무의미하다
                        json("{}", HttpStatusCode.Unauthorized)
                    }

                    else -> {
                        json(ACCOUNTS_BODY)
                    }
                }
            }

            listOf(
                async(Dispatchers.Default) { f.api.accounts() },
                async(Dispatchers.Default) { f.api.accounts() },
            ).awaitAll()

            assertEquals(1, f.tokenCalls)
        }

    @Test
    fun `갓 발급한 토큰이 401 이면 다시 발급하지 않는다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.seedToken("STALE", expiresAt = Long.MAX_VALUE, issuedAt = System.currentTimeMillis() - 2 * HOUR)
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") {
                    json(TOKEN_BODY)
                } else {
                    json("{}", HttpStatusCode.Unauthorized)
                }
            }

            assertEquals("HTTP401", assertFailsWith<NhException> { f.api.accounts() }.code)
            assertEquals("HTTP401", assertFailsWith<NhException> { f.api.accounts() }.code)
            assertEquals(1, f.tokenCalls, "1시간 창 안에서는 재발급하지 않는다")
        }

    @Test
    fun `400 IGW40043 이면 401 처럼 한 번 재발급하고 한 번 재시도한다`() =
        runTest {
            // 실기기 확인(2026-09-06) — 만료·무효 토큰에 NH 가 401 이 아니라 이 조합(400+IGW40043)을
            // 돌려준다. 발급 자체는 성공하므로 앱 키를 다시 저장해도 못 빠져나오는 문제의 근본 원인이다.
            val f = ApiFixture()
            f.ready()
            f.seedToken("STALE", expiresAt = Long.MAX_VALUE, issuedAt = System.currentTimeMillis() - 2 * HOUR)
            f.handle = { req ->
                when {
                    req.url.encodedPath == "/oauth2/token" -> json(TOKEN_BODY)
                    req.headers[HttpHeaders.Authorization] == "Bearer STALE" -> json(INVALID_TOKEN_BODY, HttpStatusCode.BadRequest)
                    else -> json(ACCOUNTS_BODY)
                }
            }

            assertEquals(1, f.api.accounts().size)
            assertEquals(1, f.tokenCalls)
            assertEquals("T1", f.vault.secrets().token)
        }

    @Test
    fun `방금 발급한 토큰이 400 IGW40043 이어도 다시 발급하지 않는다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.seedToken("FRESH0", expiresAt = Long.MAX_VALUE, issuedAt = System.currentTimeMillis())
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") {
                    json(TOKEN_BODY)
                } else {
                    json(INVALID_TOKEN_BODY, HttpStatusCode.BadRequest)
                }
            }

            assertEquals("HTTP401", assertFailsWith<NhException> { f.api.accounts() }.code)
            assertEquals(0, f.tokenCalls, "방금 발급한 토큰을 또 거부하면 자격 문제다 — 재발급을 시도하면 안 된다")
        }

    @Test
    fun `400 이어도 rsp_cd 가 IGW40043 이 아니면 재발급하지 않는다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.seedToken("T0", expiresAt = Long.MAX_VALUE, issuedAt = System.currentTimeMillis())
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") {
                    json(TOKEN_BODY)
                } else {
                    json("""{"rsp_cd":"IGW40051","rsp_msg":"잘못된 요청입니다."}""", HttpStatusCode.BadRequest)
                }
            }

            assertEquals("HTTP400", assertFailsWith<NhException> { f.api.accounts() }.code)
            assertEquals(0, f.tokenCalls)
        }

    @Test
    fun `400 본문이 JSON 이 아니어도 재발급하지 않는다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.seedToken("T0", expiresAt = Long.MAX_VALUE, issuedAt = System.currentTimeMillis())
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") {
                    json(TOKEN_BODY)
                } else {
                    respond("not json", HttpStatusCode.BadRequest)
                }
            }

            assertEquals("HTTP400", assertFailsWith<NhException> { f.api.accounts() }.code)
            assertEquals(0, f.tokenCalls)
        }

    @Test
    fun `429 는 지연만 하고 토큰을 건드리지 않는다`() =
        runTest {
            // 게이트에 runTest 의 가상 시계를 주입한다 — 실시계(nanoTime)와 가상 delay 를 섞으면
            // 코루틴 전환 등 실시간 잡음이 몇 ms 끼어들어 판정이 흔들린다(리뷰 지적).
            val f = ApiFixture(nowMs = { currentTime })
            f.ready()
            f.seedToken("T0", expiresAt = Long.MAX_VALUE, issuedAt = System.currentTimeMillis())
            var attempts = 0
            f.handle = { if (attempts++ < 2) json("{}", HttpStatusCode.TooManyRequests) else json(ACCOUNTS_BODY) }

            val before = currentTime
            assertEquals(1, f.api.accounts().size)
            // 첫 게이트는 대기가 없고(이전 슬롯이 없으므로), 재시도 사이의 백오프(300, 600)가 이미
            // 게이트 간격(250)보다 커서 두 번째·세 번째 게이트는 대기 없이 흡수된다 — 총 지연은
            // 정확히 300+600=900 이다. 게이트가 토큰을 건드리지 않는지는 tokenCalls 로 따로 잡는다.
            assertEquals(900L, currentTime - before, "지연은 정확히 300+600=900ms 여야 한다")
            assertEquals(0, f.tokenCalls)
            assertEquals(3, f.requests.size)
        }

    @Test
    fun `429 가 재시도 한도를 넘으면 포기하고 토큰도 건드리지 않는다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.seedToken("T0", expiresAt = Long.MAX_VALUE, issuedAt = System.currentTimeMillis())
            f.handle = { json("{}", HttpStatusCode.TooManyRequests) }

            assertEquals("HTTP429", assertFailsWith<NhException> { f.api.accounts() }.code)
            assertEquals(0, f.tokenCalls)
        }

    @Test
    fun `토큰 요청이 네트워크 오류면 앱키가 메시지에 남지 않는다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { throw IOException("https://api.nhplug.com:8443/oauth2/token?appkey=APPKEY&appsecretkey=APPSECRET") }

            val e = assertFailsWith<IOException> { f.api.accounts() }
            assertFalse("appkey" in (e.message ?: ""), "메시지에 appkey 가 들어가면 안 된다")
            assertFalse("APPSECRET" in (e.message ?: ""))
            // 코루틴 스택트레이스 복구가 우리 예외의 사본을 cause 로 붙인다. cause 가 null 인지가 아니라
            // 체인 어디에도 자격증명이 없는지를 검증한다.
            generateSequence(e.cause) { it.cause }.take(10).forEach { link ->
                assertFalse("appkey" in (link.message ?: ""), "cause 체인에 appkey 가 있으면 안 된다")
                assertFalse("APPSECRET" in (link.message ?: ""), "cause 체인에 시크릿이 있으면 안 된다")
            }
            assertEquals(1, f.tokenCalls, "네트워크 오류를 재시도하지 않는다")
        }

    @Test
    fun `토큰 응답이 깨졌으면 본문을 노출하지 않는다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { json("""{"oops":"APPSECRET leaked"}""") }

            val e = assertFailsWith<NhException> { f.api.accounts() }
            assertEquals("AUTH", e.code)
            assertFalse("APPSECRET" in (e.message ?: ""))
        }

    @Test
    fun `토큰 엔드포인트가 4xx 면 HTTP 코드로 보고한다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { json("{}", HttpStatusCode.BadRequest) }

            assertEquals("HTTP400", assertFailsWith<NhException> { f.api.accounts() }.code)
        }

    @Test
    fun `잠긴 상태에서는 네트워크 요청 자체를 하지 않는다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.vault.lock()

            assertFailsWith<IllegalStateException> { f.api.accounts() }
            assertEquals(0, f.requests.size)
        }

    @Test
    fun `loadResult 는 취소를 삼키지 않는다`() =
        runTest {
            assertEquals("ok", loadResult { "ok" }.getOrNull())
            assertTrue(loadResult { error("boom") }.isFailure)
            assertFailsWith<CancellationException> {
                loadResult { throw CancellationException("cancelled") }
            }
        }

    @Test
    fun `etfComponents 가 구성종목 코드 목록을 파싱한다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(ETF_COMPONENTS_BODY) }

            assertEquals(listOf("005930", "000660"), f.api.etfComponents("069500"))

            val body = (f.requests.last { it.url.encodedPath.endsWith("/etfComponents") }.body as TextContent).text
            assertTrue("\"iem_cd\":\"069500\"" in body, body)
        }

    @Test
    fun `etfComponents 가 cts_flag=Y 일 때 다음 페이지를 이어 받는다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            var page = 0
            f.handle = { req ->
                when {
                    req.url.encodedPath == "/oauth2/token" -> {
                        json(TOKEN_BODY)
                    }

                    page++ == 0 -> {
                        json(
                            """{"rsp_cd":"00000","rsp_msg":"완료","Output_0":[{"iem_cd":"005930"}]}""",
                            extra = headersOf("cts" to listOf("C1"), "cts_flag" to listOf("Y")),
                        )
                    }

                    else -> {
                        json("""{"rsp_cd":"00000","rsp_msg":"완료","Output_0":[{"iem_cd":"000660"}]}""")
                    }
                }
            }

            assertEquals(listOf("005930", "000660"), f.api.etfComponents("069500"))
            val second = f.requests.last()
            assertEquals("C1", second.headers["cts"])
            assertEquals("Y", second.headers["cts_flag"])
        }

    @Test
    fun `etfComponents 는 ISIN 을 단축코드로 바꾸고 공백과 중복을 정리한다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(ETF_COMPONENTS_MIXED_CODES_BODY)
            }

            assertEquals(listOf("005930", "000660"), f.api.etfComponents("069500"))
        }

    @Test
    fun `dailyBars 가 Output_1 을 Bar 로 옮기고 일자 오름차순으로 정렬한다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(DAILY_BARS_BODY) }

            val bars = f.api.dailyBars("005930", count = 3)

            assertEquals(listOf("20260102", "20260103", "20260104"), bars.map { it.date })
            assertEquals(listOf(70000, 71000, 72000), bars.map { it.close }, "앞뒤 공백이 있는 \" 71000\" 도 파싱돼야 한다")
            assertEquals(listOf(69000, 70000, 71000), bars.map { it.refPrice })
            assertEquals(1, f.requests.count { it.url.encodedPath.endsWith("/period") }, "한 번에 다 왔으면 더 부르지 않는다")

            val body = (f.requests.last { it.url.encodedPath.endsWith("/period") }.body as TextContent).text
            assertTrue("\"market_cd\":\"UNT\"" in body, body)
            assertTrue("\"iem_cd\":\"005930\"" in body, body)
            assertTrue("\"mrkt_div_cls_code\":\"1\"" in body, body)
            assertTrue("\"gubun\":\"1\"" in body, body)
            assertTrue("\"array_cnt\":\"3\"" in body, body)
        }

    @Test
    fun `dailyBars 가 최신부터 내려온 응답도 같은 결과로 정렬한다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(DAILY_BARS_DESC_BODY) }

            val bars = f.api.dailyBars("005930", count = 3)

            assertEquals(listOf("20260102", "20260103", "20260104"), bars.map { it.date })
        }

    @Test
    fun `dailyBars 는 stck_prpr 가 양의 정수가 아니면 그 날을 버린다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(DAILY_BARS_BAD_CLOSE_BODY)
            }

            val bars = f.api.dailyBars("005930", count = 1)

            assertEquals(listOf("20260102"), bars.map { it.date })
            assertEquals(70000, bars.single().close)
        }

    @Test
    fun `dailyBars 는 stck_sdpr 가 양의 정수가 아니면 refPrice 0 이고 exRight 는 false 다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(DAILY_BARS_BAD_REF_BODY)
            }

            val bar = f.api.dailyBars("005930", count = 1).single()

            assertEquals(0, bar.refPrice)
            assertFalse(bar.exRight, "기준가가 무효면 fcam_mod_cls_code 가 있어도 exRight 는 false 여야 한다")
        }

    @Test
    fun `dailyBars 가 fcam_mod_cls_code=01 을 exRight=true 로 옮긴다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(DAILY_BARS_EX_RIGHT_BODY)
            }

            assertTrue(
                f.api
                    .dailyBars("005930", count = 1)
                    .single()
                    .exRight,
            )
        }

    @Test
    fun `dailyBars 가 flng_cls_code=02 배당락은 exRight=false 로 둔다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(DAILY_BARS_DIVIDEND_BODY)
            }

            assertFalse(
                f.api
                    .dailyBars("005930", count = 1)
                    .single()
                    .exRight,
            )
        }

    @Test
    fun `dailyBars 는 cts 없이 잘린 응답을 edate 로 이어 받고 요청 건수에 닿으면 멈춘다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            var page = 0
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") {
                    json(TOKEN_BODY)
                } else {
                    when (page++) {
                        0 -> json(PERIOD_PAGE_1_BODY)
                        1 -> json(PERIOD_PAGE_2_BODY)
                        else -> json(PERIOD_PAGE_3_BODY)
                    }
                }
            }

            val bars = f.api.dailyBars("005930", count = 5)

            assertEquals(
                listOf("20260106", "20260107", "20260108", "20260109", "20260110"),
                bars.map { it.date },
            )
            val periodBodies = f.requests.filter { it.url.encodedPath.endsWith("/period") }.map { (it.body as TextContent).text }
            assertEquals(3, periodBodies.size)
            assertTrue("\"edate\":\"\"" in periodBodies[0], periodBodies[0])
            assertTrue("\"array_cnt\":\"5\"" in periodBodies[0], periodBodies[0])
            assertTrue("\"edate\":\"20260108\"" in periodBodies[1], periodBodies[1])
            assertTrue("\"array_cnt\":\"3\"" in periodBodies[1], periodBodies[1])
            assertTrue("\"edate\":\"20260106\"" in periodBodies[2], periodBodies[2])
            assertTrue("\"array_cnt\":\"1\"" in periodBodies[2], periodBodies[2])
        }

    @Test
    fun `dailyBars 는 가장 오래된 일자가 그대로면 무한 루프 없이 멈춘다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(DAILY_BARS_SAME_OLDEST_BODY)
            }

            val bars = f.api.dailyBars("005930", count = 10)

            assertEquals(listOf("20260101", "20260102"), bars.map { it.date })
            assertEquals(2, f.requests.count { it.url.encodedPath.endsWith("/period") }, "진전이 없으면 멈춰야 한다")
        }

    @Test
    fun `dailyBars 는 매번 진전이 있어도 페이지 상한에서 멈춘다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            var page = 0
            val start = LocalDate.of(2026, 1, 31)
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") {
                    json(TOKEN_BODY)
                } else {
                    // 매 페이지 새 날짜를 하나씩만 준다 — count(1100) 에는 한참 못 미치지만
                    // 진전은 계속 있으므로 page cap 이 없으면 이 루프는 끝나지 않는다.
                    val d = start.minusDays(page.toLong()).format(FMT)
                    page++
                    json("""{"rsp_cd":"00000","rsp_msg":"완료","Output_1":[{"bsop_date":"$d","stck_prpr":"100","stck_sdpr":"100"}]}""")
                }
            }

            val bars = f.api.dailyBars("005930", count = 1_100)

            assertEquals(20, bars.size, "MAX_PAGES(20) 에서 멈춰야 한다")
            assertEquals(20, f.requests.count { it.url.encodedPath.endsWith("/period") })
        }

    @Test
    fun `dailyBars 는 bsop_date 가 빈 문자열인 행을 버리고 죽지 않는다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(DAILY_BARS_BLANK_DATE_BODY)
            }

            val bars = f.api.dailyBars("005930", count = 1)

            assertEquals(listOf("20260102"), bars.map { it.date }, "빈 bsop_date 행은 빠지고 공백 채움 유효 행만 남아야 한다")
            assertEquals(1, f.requests.count { it.url.encodedPath.endsWith("/period") }, "count 를 채웠으면 더 부르지 않는다")
        }

    @Test
    fun `dailyBars 는 bsop_date 가 자릿수만 맞고 달력에 없으면 그 행을 버린다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(DAILY_BARS_INVALID_CALENDAR_DATE_BODY)
            }

            val bars = f.api.dailyBars("005930", count = 1)

            assertEquals(listOf("20260102"), bars.map { it.date }, "13월처럼 자릿수만 맞는 날짜는 빈 값과 같이 버려져야 한다")
        }

    @Test
    fun `dailyBars 는 오래된 일자가 뒤로 가지 않으면 두 번째 호출에서 멈춘다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            var page = 0
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") {
                    json(TOKEN_BODY)
                } else {
                    when (page++) {
                        0 -> json(PERIOD_REGRESS_A_BODY)
                        else -> json(PERIOD_REGRESS_B_BODY)
                    }
                }
            }

            f.api.dailyBars("005930", count = 10)

            assertEquals(
                2,
                f.requests.count { it.url.encodedPath.endsWith("/period") },
                "oldest 가 뒤로 가지 않으면(역행 포함) 세 번째 호출을 하면 안 된다",
            )
        }

    @Test
    fun `dailyBars 는 Output_1 이 없고 정상 응답이면 빈 목록이다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") {
                    json(TOKEN_BODY)
                } else {
                    json("""{"rsp_cd":"00000","rsp_msg":"조회가 완료되었습니다."}""")
                }
            }

            assertTrue(f.api.dailyBars("005930", count = 5).isEmpty())
        }

    @Test
    fun `dailyBars 의 업무 오류 응답은 NhException 이 되고 메시지가 그대로 담긴다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") {
                    json(TOKEN_BODY)
                } else {
                    json("""{"rsp_cd":"40010","rsp_msg":"종목코드 항목을 입력하세요."}""")
                }
            }

            val e = assertFailsWith<NhException> { f.api.dailyBars("005930", count = 5) }
            assertEquals("40010", e.code)
            assertEquals("종목코드 항목을 입력하세요.", e.message)
        }

    @Test
    fun `dailyBars 는 필요한 만큼 모이면 cts 가 남아 있어도 더 받지 않는다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            // 서버가 array_cnt(remaining=7) 를 무시하고 3봉씩, cts_flag=Y·매번 새 cts 로 끝없이 준다고 가정한다 —
            // enough() 가 없으면 cts 가 절대 멈추지 않아(반복도 안 되고 항상 Y) MAX_PAGES 까지 돈다.
            var page = 0
            val start = LocalDate.of(2026, 3, 31)
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") {
                    json(TOKEN_BODY)
                } else {
                    val base = page * 3
                    val rows =
                        (0 until 3).joinToString(",") { i ->
                            val d = start.minusDays((base + i).toLong()).format(FMT)
                            """{"bsop_date":"$d","stck_prpr":"${100 - base - i}","stck_sdpr":"${100 - base - i}"}"""
                        }
                    page++
                    json(
                        """{"rsp_cd":"00000","rsp_msg":"완료","Output_1":[$rows]}""",
                        extra = headersOf("cts" to listOf("P$page"), "cts_flag" to listOf("Y")),
                    )
                }
            }

            val bars = f.api.dailyBars("005930", count = 7)

            assertEquals(
                3,
                f.requests.count { it.url.encodedPath.endsWith("/period") },
                "9봉이 모여 7건을 채운 3번째 페이지에서 cts 를 더 따라가면 안 된다",
            )
            assertEquals(
                listOf("20260325", "20260326", "20260327", "20260328", "20260329", "20260330", "20260331"),
                bars.map { it.date },
            )
        }

    @Test
    fun `요청 사이에는 최소 250ms 간격을 앱 전체에서 지킨다`() =
        runTest {
            // 게이트에 runTest 의 가상 시계를 주입한다 — 실시계(nanoTime)를 쓰면 두 예약 사이에
            // 실행되는 코드(JSON 파싱, 코루틴 전환)가 실시간으로 몇 ms 를 먹어 간격이 근소하게
            // 모자랄 수 있다(리뷰 지적). 가상 시계는 delay 로만 흐르므로 결정적이다.
            val f = ApiFixture(nowMs = { currentTime })
            f.ready()
            f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json(ACCOUNTS_BODY) }

            f.api.accounts() // 토큰 발급 + 첫 gate 요청 — 아직 예약이 없으니 대기 없이 통과한다
            val afterFirst = currentTime
            f.api.accounts() // 토큰은 캐시되니 gate 요청 하나뿐 — 첫 요청 슬롯으로부터 정확히 250ms 뒤에 시작해야 한다

            assertEquals(250L, currentTime - afterFirst, "두 번째 요청은 첫 요청 슬롯 뒤 정확히 250ms 에 시작해야 한다")
        }
}
