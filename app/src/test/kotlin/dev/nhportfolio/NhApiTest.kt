package dev.nhportfolio

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import dev.nhportfolio.api.NhApi
import dev.nhportfolio.api.NhException
import dev.nhportfolio.api.loadResult
import dev.nhportfolio.model.Account
import dev.nhportfolio.security.Vault
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

private class ApiFixture {
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
            assertTrue(
                token.body.contentType
                    .toString()
                    .startsWith("application/x-www-form-urlencoded"),
            )
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
    fun `429 는 지연만 하고 토큰을 건드리지 않는다`() =
        runTest {
            val f = ApiFixture()
            f.ready()
            f.seedToken("T0", expiresAt = Long.MAX_VALUE, issuedAt = System.currentTimeMillis())
            var attempts = 0
            f.handle = { if (attempts++ < 2) json("{}", HttpStatusCode.TooManyRequests) else json(ACCOUNTS_BODY) }

            val before = currentTime
            assertEquals(1, f.api.accounts().size)
            assertEquals(900, currentTime - before, "지연은 300+600=900ms 여야 한다")
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
}
