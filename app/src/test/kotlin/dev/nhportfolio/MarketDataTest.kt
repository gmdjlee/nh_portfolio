package dev.nhportfolio

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import dev.nhportfolio.api.NhApi
import dev.nhportfolio.api.NhException
import dev.nhportfolio.market.MarketData
import dev.nhportfolio.market.SyncState
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val FMT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

private const val TOKEN_BODY = """{"access_token":"T1","token_type":"Bearer","expires_in":86400}"""

private fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = body,
    status = status,
    headers = Headers.build { append(HttpHeaders.ContentType, "application/json") },
)

/** [count]일치 YYYYMMDD 문자열, [start] 부터 하루씩. */
private fun dateSeq(
    count: Int,
    start: LocalDate = LocalDate.of(2020, 1, 1),
): List<String> = (0 until count).map { start.plusDays(it.toLong()).format(FMT) }

private fun writeUniverse(
    dir: File,
    at: String,
    codes: List<String>,
) {
    File(dir, "universe.json").writeText(
        """{"at":"$at","codes":[${codes.joinToString(",") { "\"$it\"" }}]}""",
    )
}

private fun writeBars(
    dir: File,
    code: String,
    dates: List<String>,
    closes: List<Int>,
    ex: Map<String, Int> = emptyMap(),
) {
    File(dir, "bars").mkdirs()
    val exJson = ex.entries.joinToString(",") { "\"${it.key}\":${it.value}" }
    File(dir, "bars/$code.json").writeText(
        """{"dates":[${dates.joinToString(",") { "\"$it\"" }}],"closes":[${closes.joinToString(",")}],"ex":{$exJson}}""",
    )
}

/** 우상향 종가 — 성숙한 날은 항상 자기 이동평균보다 위(above)라, 시장폭이 1.0 으로 손으로 검증하기 쉽다. */
private fun writeUpStock(
    dir: File,
    code: String,
    dates: List<String>,
    base: Int = 10_000,
) = writeBars(dir, code, dates, dates.indices.map { base + it })

/** 평평한(constant) 종가 — 성숙하면 자기 이동평균과 같아서(above 아님) 유효·비above 로 잡힌다. */
private fun writeFlatStock(
    dir: File,
    code: String,
    dates: List<String>,
    value: Int = 10_000,
) = writeBars(dir, code, dates, List(dates.size) { value })

private fun writeCorrupt(
    dir: File,
    code: String,
) {
    File(dir, "bars").mkdirs()
    File(dir, "bars/$code.json").writeText("{ 이건 JSON 이 아니다")
}

/** 유효 종목 30 하한을 채우는 우상향 베이스라인. 항상 above 라 breadth=1.0 이 나온다(손 계산 가능). */
private fun writeBaseline(
    dir: File,
    dates: List<String>,
    count: Int = 30,
): List<String> {
    val codes = (0 until count).map { "U%02d".format(it) }
    codes.forEach { writeUpStock(dir, it, dates) }
    return codes
}

/** /period 요청을 그대로 반영해 array_cnt 만큼, edate(비었으면 오늘)부터 거슬러 올라간 봉을 만든다. */
private fun MockRequestHandleScope.periodResponse(request: HttpRequestData): HttpResponseData {
    val input =
        Json
            .parseToJsonElement((request.body as TextContent).text)
            .jsonObject
            .getValue("Input_0")
            .jsonObject
    val count =
        input
            .getValue("array_cnt")
            .jsonPrimitive.content
            .toInt()
    val edate = input.getValue("edate").jsonPrimitive.content
    val end = if (edate.isBlank()) LocalDate.now() else LocalDate.parse(edate, FMT)
    val rows =
        (0 until count).joinToString(",") { i ->
            val d = end.minusDays(i.toLong()).format(FMT)
            val price = 10_000 + count - i
            """{"bsop_date":"$d","stck_prpr":"$price","stck_sdpr":"$price"}"""
        }
    return json("""{"rsp_cd":"00000","rsp_msg":"완료","Output_1":[$rows]}""")
}

/** NhApiTest 의 ApiFixture 와 같은 구성 — 인메모리 DataStore 기반 Vault 로 NhApi 를 만든다. */
private class MdFixture {
    val dir: File = createTempDirectory().toFile()
    private val storeDir: File = createTempDirectory().toFile()
    private val macKey = SecretKeySpec(ByteArray(32) { 7 }, "HmacSHA256")

    private val store =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ) { File(storeDir, "api.preferences_pb") }

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

    val market = MarketData(api, dir)

    suspend fun ready() {
        vault.setPin("135790".toCharArray())
        vault.update { it.copy(appKey = "APPKEY", appSecret = "APPSECRET") }
    }

    fun periodRequests() = requests.count { it.url.encodedPath.endsWith("/period") }

    fun etfRequests() = requests.count { it.url.encodedPath.endsWith("/etfComponents") }
}

class MarketDataTest {
    // ---- cached() / cachedDays() : 파일시스템만, 네트워크 없음 ----

    @Test
    fun `캐시가 없으면 cached는 null이고 cachedDays는 0이다`() {
        val f = MdFixture()
        assertNull(f.market.cached())
        assertEquals(0, f.market.cachedDays())
    }

    @Test
    fun `손상된 종목 파일은 없는 것처럼 취급되고 계산은 죽지 않는다`() {
        val f = MdFixture()
        writeUniverse(f.dir, at = "20200101", codes = listOf("A", "BAD"))
        writeUpStock(f.dir, "A", dateSeq(5))
        writeCorrupt(f.dir, "BAD")

        assertEquals(5, f.market.cachedDays(), "손상된 종목은 달력에 기여하지 않는다")
        assertNull(f.market.cached(), "유효 종목이 30 미만이라 null 이지만 예외는 없다")
    }

    @Test
    fun `종목마다 일자가 어긋나도 달력은 전체 일자의 합집합이다`() {
        val f = MdFixture()
        writeUniverse(f.dir, at = "20200101", codes = listOf("A", "B"))
        writeUpStock(f.dir, "A", listOf("20200101", "20200103", "20200105"))
        writeUpStock(f.dir, "B", listOf("20200102", "20200104", "20200106"))

        assertEquals(6, f.market.cachedDays())
    }

    @Test
    fun `거래일이 500일 미만이면 cached는 null이고 cachedDays는 실제 수를 준다`() {
        val f = MdFixture()
        val dates = dateSeq(450)
        writeUniverse(f.dir, at = "20200101", codes = writeBaseline(f.dir, dates))

        assertNull(f.market.cached())
        assertEquals(450, f.market.cachedDays())
    }

    @Test
    fun `거래일이 700일이면 신호가 나오고 window는 756 미만이다`() {
        val f = MdFixture()
        val dates = dateSeq(700)
        writeUniverse(f.dir, at = "20200101", codes = writeBaseline(f.dir, dates))

        val signal = assertNotNull(f.market.cached())
        assertEquals(700, f.market.cachedDays())
        assertTrue(signal.window < 756, "window=${signal.window}")
    }

    @Test
    fun `종목 내부의 빈 날은 직전 값으로 채워 분모에 들어간다`() {
        val f = MdFixture()
        val dates = dateSeq(500)
        val baseline = writeBaseline(f.dir, dates)
        // 3일에 한 번만 값이 있는 평평한 종목 — 직전값 채움이 없으면 유효 관측(count>=150)을 못 채운다.
        val sparse = dates.filterIndexed { i, _ -> i % 3 == 0 }
        writeFlatStock(f.dir, "GAP", sparse)
        writeUniverse(f.dir, at = "20200101", codes = baseline + "GAP")

        val signal = assertNotNull(f.market.cached())
        assertEquals(30.0 / 31.0, signal.breadth, "GAP 이 직전값으로 이어져 유효(31)·비above(0)로 잡혀야 한다")
    }

    @Test
    fun `상장 전 구간은 채우지 않아 그 종목이 분모에서 빠진다`() {
        val f = MdFixture()
        val dates = dateSeq(500)
        val baseline = writeBaseline(f.dir, dates)
        // 마지막 50일만 상장된 평평한 종목 — 앞을 채우면(백필 버그) 200일 창의 유효 관측이 150을 넘어 끼어든다.
        writeFlatStock(f.dir, "LATE", dates.takeLast(50))
        writeUniverse(f.dir, at = "20200101", codes = baseline + "LATE")

        val signal = assertNotNull(f.market.cached())
        assertEquals(1.0, signal.breadth, "상장 전 50일은 0(미정의)이라 LATE 는 이 시점에도 분모에서 빠져야 한다")
    }

    // ---- sync() : MockEngine ----

    @Test
    fun `sync은 유니버스 1회와 종목 수만큼 호출하며 진행률을 순서대로 흘린다`() =
        runTest {
            val f = MdFixture()
            f.ready()
            val today = LocalDate.now()
            val codes = listOf("005930", "000660", "005380")
            val history = dateSeq(400, today.minusDays(400)) // 어제까지 400일 — 오늘 하루만 모자란다
            codes.forEach { writeUpStock(f.dir, it, history) }
            f.handle = { req ->
                when {
                    req.url.encodedPath == "/oauth2/token" -> {
                        json(TOKEN_BODY)
                    }

                    req.url.encodedPath.endsWith("/etfComponents") -> {
                        json(
                            """{"rsp_cd":"00000","rsp_msg":"완료",""" +
                                """"Output_0":[${codes.joinToString(",") { "{\"iem_cd\":\"$it\"}" }}]}""",
                        )
                    }

                    else -> {
                        periodResponse(req)
                    }
                }
            }

            val states = f.market.sync().toList()

            assertEquals(1, f.etfRequests())
            assertEquals(codes.size, f.periodRequests())
            assertEquals(
                listOf(
                    SyncState.Running(0, 3),
                    SyncState.Running(1, 3),
                    SyncState.Running(2, 3),
                    SyncState.Running(3, 3),
                    SyncState.Done(0),
                ),
                states,
            )
            val body = (f.requests.last { it.url.encodedPath.endsWith("/period") }.body as TextContent).text
            assertTrue("\"array_cnt\":\"6\"" in body, body) // 어제까지 있었으니 gap(1)+여유(5)
        }

    @Test
    fun `종목 하나가 실패해도 나머지는 저장되고 신호가 계산되며 실패 수가 표면화된다`() =
        runTest {
            val f = MdFixture()
            f.ready()
            val today = LocalDate.now()
            val history = dateSeq(500, today.minusDays(500)) // 어제까지 500일
            val okCodes = (0 until 30).map { "U%02d".format(it) }
            val failCode = "ZFAIL0"
            (okCodes + failCode).forEach { writeUpStock(f.dir, it, history) }
            writeUniverse(f.dir, at = today.format(FMT), codes = okCodes + failCode)

            f.handle = { req ->
                val text = (req.body as? TextContent)?.text.orEmpty()
                when {
                    req.url.encodedPath == "/oauth2/token" -> json(TOKEN_BODY)
                    "\"iem_cd\":\"$failCode\"" in text -> json("""{"rsp_cd":"40010","rsp_msg":"조회 실패"}""")
                    else -> periodResponse(req)
                }
            }

            val states = f.market.sync().toList()

            assertEquals(SyncState.Done(1), states.last())
            assertEquals(501, f.market.cachedDays(), "성공한 종목은 오늘까지 늘어나 달력이 501일이 된다")
            assertNotNull(f.market.cached(), "종목 하나가 실패해도 나머지로 신호는 계산된다")
        }

    @Test
    fun `유니버스가 90일 지나면 다시 받고 빠진 종목의 파일을 지운다`() =
        runTest {
            val f = MdFixture()
            f.ready()
            val today = LocalDate.now()
            val keep = "111111"
            val drop = "222222"
            val added = "333333"
            writeUniverse(f.dir, at = today.minusDays(91).format(FMT), codes = listOf(keep, drop))
            writeUpStock(f.dir, keep, listOf(today.format(FMT)))
            writeUpStock(f.dir, drop, listOf(today.format(FMT)))

            f.handle = { req ->
                when {
                    req.url.encodedPath == "/oauth2/token" -> {
                        json(TOKEN_BODY)
                    }

                    req.url.encodedPath.endsWith("/etfComponents") -> {
                        json("""{"rsp_cd":"00000","rsp_msg":"완료","Output_0":[{"iem_cd":"$keep"},{"iem_cd":"$added"}]}""")
                    }

                    else -> {
                        periodResponse(req)
                    }
                }
            }

            f.market.sync().toList()

            assertEquals(1, f.etfRequests())
            assertFalse(File(f.dir, "bars/$drop.json").exists(), "유니버스에서 빠진 종목의 파일은 지워야 한다")
            assertTrue(File(f.dir, "bars/$keep.json").exists())
        }

    @Test
    fun `유니버스 응답이 비어 있으면 캐시를 지우지 않고 기존 목록으로 진행한다`() =
        runTest {
            val f = MdFixture()
            f.ready()
            val today = LocalDate.now()
            val codeA = "111111"
            val codeB = "222222"
            writeUniverse(f.dir, at = today.minusDays(91).format(FMT), codes = listOf(codeA, codeB))
            writeUpStock(f.dir, codeA, listOf(today.format(FMT)))
            writeUpStock(f.dir, codeB, listOf(today.format(FMT)))
            val universeBefore = File(f.dir, "universe.json").readText()

            f.handle = { req ->
                when {
                    req.url.encodedPath == "/oauth2/token" -> json(TOKEN_BODY)
                    req.url.encodedPath.endsWith("/etfComponents") -> json("""{"rsp_cd":"00000","rsp_msg":"완료","Output_0":[]}""")
                    else -> periodResponse(req)
                }
            }

            val states = f.market.sync().toList()

            assertEquals(1, f.etfRequests())
            assertEquals(SyncState.Running(0, 2), states.first(), "빈 응답이면 캐시된 2개 목록으로 계속 진행해야 한다")
            assertEquals(SyncState.Done(0), states.last())
            assertTrue(File(f.dir, "bars/$codeA.json").exists(), "빈 응답이 캐시된 종목의 봉 파일을 지우면 안 된다")
            assertTrue(File(f.dir, "bars/$codeB.json").exists())
            assertEquals(universeBefore, File(f.dir, "universe.json").readText(), "빈 응답으로 universe.json 을 덮어쓰면 안 된다")
        }

    @Test
    fun `유니버스 파일의 at이 날짜로 파싱되지 않아도 갱신으로 복구한다`() =
        runTest {
            val f = MdFixture()
            f.ready()
            val today = LocalDate.now()
            val code = "111111"
            writeUniverse(f.dir, at = "garbage", codes = listOf(code))
            writeUpStock(f.dir, code, listOf(today.format(FMT))) // 오늘까지 있으니 이 종목 자체는 요청되지 않는다

            f.handle = { req ->
                when {
                    req.url.encodedPath == "/oauth2/token" -> {
                        json(TOKEN_BODY)
                    }

                    req.url.encodedPath.endsWith("/etfComponents") -> {
                        json("""{"rsp_cd":"00000","rsp_msg":"완료","Output_0":[{"iem_cd":"$code"}]}""")
                    }

                    else -> {
                        periodResponse(req)
                    }
                }
            }

            val states = f.market.sync().toList()

            assertEquals(1, f.etfRequests(), "at 이 깨졌으면 손상(=없음)으로 보고 다시 받아야 한다")
            assertEquals(SyncState.Done(0), states.last())
            val universeText = File(f.dir, "universe.json").readText()
            assertTrue("\"at\":\"${today.format(FMT)}\"" in universeText, universeText)
        }

    @Test
    fun `유니버스가 90일 미만이면 다시 받지 않는다`() =
        runTest {
            val f = MdFixture()
            f.ready()
            val today = LocalDate.now().format(FMT)
            val code = "111111"
            writeUniverse(f.dir, at = today, codes = listOf(code))
            writeUpStock(f.dir, code, listOf(today)) // 오늘까지 있으니 종목 요청도 없다

            f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else periodResponse(req) }

            f.market.sync().toList()

            assertEquals(0, f.etfRequests())
            assertEquals(0, f.periodRequests())
        }

    @Test
    fun `손상된 종목 파일만 다시 받고 신선한 파일은 요청하지 않는다`() =
        runTest {
            val f = MdFixture()
            f.ready()
            val today = LocalDate.now().format(FMT)
            val fresh = "111111"
            val corrupt = "222222"
            writeUniverse(f.dir, at = today, codes = listOf(fresh, corrupt))
            writeUpStock(f.dir, fresh, listOf(today))
            writeCorrupt(f.dir, corrupt)

            f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else periodResponse(req) }

            f.market.sync().toList()

            assertEquals(1, f.periodRequests(), "신선한 종목은 건너뛰고 손상된 종목만 요청해야 한다")
            val body = (f.requests.last { it.url.encodedPath.endsWith("/period") }.body as TextContent).text
            assertTrue("\"iem_cd\":\"$corrupt\"" in body, body)
            assertTrue("\"array_cnt\":\"1100\"" in body, "캐시 없는(손상=없음) 종목은 최초 백필 건수를 요청해야 한다: $body")
        }

    @Test
    fun `일자가 깨진 종목 파일은 없는 것으로 보고 다시 받는다`() =
        runTest {
            val f = MdFixture()
            f.ready()
            val today = LocalDate.now().format(FMT)
            val code = "111111"
            writeUniverse(f.dir, at = today, codes = listOf(code))
            writeBars(f.dir, code, dates = listOf("garbage"), closes = listOf(100))

            f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else periodResponse(req) }

            f.market.sync().toList()

            assertEquals(1, f.periodRequests(), "일자가 깨졌으면 없는 파일로 보고 다시 받아야 한다")
            val body = (f.requests.last { it.url.encodedPath.endsWith("/period") }.body as TextContent).text
            assertTrue("\"array_cnt\":\"1100\"" in body, "캐시 없는(손상=없음) 종목이라 최초 백필 건수를 요청해야 한다: $body")
            assertFalse(
                "garbage" in File(f.dir, "bars/$code.json").readText(),
                "손상된 일자가 병합을 거쳐 파일에 그대로 남으면 안 된다",
            )
            assertEquals(1100, f.market.cachedDays(), "다시 받은 봉만 반영돼야 한다")
        }

    @Test
    fun `일자가 8자리 숫자이지만 달력에 없는(13월) 종목 파일은 없는 것으로 보고 다시 받는다`() =
        runTest {
            val f = MdFixture()
            f.ready()
            val today = LocalDate.now().format(FMT)
            val code = "111111"
            writeUniverse(f.dir, at = today, codes = listOf(code))
            writeBars(f.dir, code, dates = listOf("20261332"), closes = listOf(100))

            f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else periodResponse(req) }

            f.market.sync().toList()

            assertEquals(1, f.periodRequests(), "13월처럼 자릿수만 맞는 날짜는 없는 파일로 보고 다시 받아야 한다")
            assertFalse(
                "20261332" in File(f.dir, "bars/$code.json").readText(),
                "무효한 일자가 병합을 거쳐 파일에 그대로 남으면 안 된다",
            )
            assertEquals(1100, f.market.cachedDays(), "다시 받은 봉만 반영돼야 한다")
        }

    @Test
    fun `종가에 0 이하가 섞인 종목 파일은 없는 것으로 보고 다시 받는다`() =
        runTest {
            val f = MdFixture()
            f.ready()
            val today = LocalDate.now().format(FMT)
            val code = "111111"
            writeUniverse(f.dir, at = today, codes = listOf(code))
            writeBars(f.dir, code, dates = listOf("20260101", "20260102"), closes = listOf(0, 100))

            f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else periodResponse(req) }

            f.market.sync().toList()

            assertEquals(1, f.periodRequests(), "종가에 0 이하가 섞였으면 없는 파일로 보고 다시 받아야 한다")
            val body = (f.requests.last { it.url.encodedPath.endsWith("/period") }.body as TextContent).text
            assertTrue("\"array_cnt\":\"1100\"" in body, "캐시 없는(손상=없음) 종목이라 최초 백필 건수를 요청해야 한다: $body")
        }

    @Test
    fun `저장된 마지막 일자가 미래여도(시계 역행) count 를 1로 낮춰 요청한다`() =
        runTest {
            val f = MdFixture()
            f.ready()
            val today = LocalDate.now()
            val code = "111111"
            writeUniverse(f.dir, at = today.format(FMT), codes = listOf(code))
            writeUpStock(f.dir, code, listOf(today.plusDays(10).format(FMT)))

            f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else periodResponse(req) }

            val states = f.market.sync().toList()

            assertEquals(1, f.periodRequests())
            val body = (f.requests.last { it.url.encodedPath.endsWith("/period") }.body as TextContent).text
            assertTrue("\"array_cnt\":\"1\"" in body, "미래로 남은 마지막 일자는 음수 gap 을 1로 낮춰 요청해야 한다: $body")
            assertEquals(SyncState.Done(0), states.last())
        }

    @Test
    fun `권리락 항목은 증분 병합을 거쳐도 살아남고 수정주가 보정에 반영된다`() =
        runTest {
            val f = MdFixture()
            f.ready()
            val today = LocalDate.now()
            val dates = dateSeq(500, today.minusDays(499))
            val baseline = writeBaseline(f.dir, dates)

            // EXR: 옛 구간(1~479일)은 원본 5000원, 480일째 권리락(기준가 10000원)으로 2배 분할됐다.
            // 보정이 살아남으면 전 구간이 10000원으로 평평해져 마지막 날 종가가 자기 200일선과
            // 같아(above 아님) 진다. 병합이 ex 항목을 잃으면 옛 구간이 5000원에 머물러 마지막 날
            // 종가(10000)가 자기 200일선(약 5525)보다 높아져(above) 버린다 — 그 차이로 검증한다.
            val exDate = dates[479]
            val closesBeforeSync = List(490) { i -> if (i < 479) 5_000 else 10_000 }
            writeBars(f.dir, "EXR", dates.take(490), closesBeforeSync, ex = mapOf(exDate to 10_000))
            writeUniverse(f.dir, at = today.format(FMT), codes = baseline + "EXR")

            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") {
                    json(TOKEN_BODY)
                } else {
                    // 이 테스트의 /period 요청은 EXR 갱신 하나뿐이다(baseline 은 이미 오늘 날짜라
                    // 건너뛴다). 새로 받는 구간도 권리락 없이 10000원 그대로다.
                    val input =
                        Json
                            .parseToJsonElement((req.body as TextContent).text)
                            .jsonObject
                            .getValue("Input_0")
                            .jsonObject
                    val count =
                        input
                            .getValue("array_cnt")
                            .jsonPrimitive.content
                            .toInt()
                    val edate = input.getValue("edate").jsonPrimitive.content
                    val end = if (edate.isBlank()) LocalDate.now() else LocalDate.parse(edate, FMT)
                    val rows =
                        (0 until count).joinToString(",") { i ->
                            val d = end.minusDays(i.toLong()).format(FMT)
                            """{"bsop_date":"$d","stck_prpr":"10000","stck_sdpr":"10000"}"""
                        }
                    json("""{"rsp_cd":"00000","rsp_msg":"완료","Output_1":[$rows]}""")
                }
            }

            f.market.sync().toList()

            val saved = File(f.dir, "bars/EXR.json").readText()
            assertTrue("\"$exDate\":10000" in saved, "옛 권리락 항목이 증분 병합 뒤에도 남아 있어야 한다: $saved")

            val signal = assertNotNull(f.market.cached())
            assertEquals(30.0 / 31.0, signal.breadth, "보정이 살아남았으면 EXR 은 평평해져 above 가 아니어야 한다")
        }

    @Test
    fun `유니버스 조회가 실패해도 캐시된 목록으로 계속 진행한다`() =
        runTest {
            val f = MdFixture()
            f.ready()
            val today = LocalDate.now()
            val code = "111111"
            writeUniverse(f.dir, at = today.minusDays(91).format(FMT), codes = listOf(code))
            writeUpStock(f.dir, code, listOf(today.minusDays(1).format(FMT))) // 오늘분 갱신이 필요하다

            f.handle = { req ->
                when {
                    req.url.encodedPath == "/oauth2/token" -> json(TOKEN_BODY)
                    req.url.encodedPath.endsWith("/etfComponents") -> json("""{"rsp_cd":"40010","rsp_msg":"실패"}""")
                    else -> periodResponse(req)
                }
            }

            val states = f.market.sync().toList()

            assertEquals(SyncState.Done(0), states.last(), "유니버스 실패는 종목 실패가 아니다")
            assertEquals(1, f.periodRequests(), "캐시된 목록의 종목은 정상적으로 계속 갱신된다")
        }

    @Test
    fun `유니버스가 없고 조회마저 실패하면 예외가 그대로 전파된다`() =
        runTest {
            val f = MdFixture()
            f.ready()
            f.handle = { req ->
                if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else json("""{"rsp_cd":"40010","rsp_msg":"실패"}""")
            }

            val e = assertFailsWith<NhException> { f.market.sync().toList() }
            assertEquals("40010", e.code)
        }
}
