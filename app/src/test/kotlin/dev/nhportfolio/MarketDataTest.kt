package dev.nhportfolio

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import dev.nhportfolio.api.NhApi
import dev.nhportfolio.api.NhException
import dev.nhportfolio.market.Band
import dev.nhportfolio.market.MarketData
import dev.nhportfolio.market.Signal
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
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

/** `index/069500.json` 을 직접 쓴다. 이미 최신 지수 파일을 깔아 두면 그 시나리오의 동기화
 *  요청 수 계산에 지수가 끼어들지 않는다 — sync() 는 종목처럼 지수도 매번 자기 파일의
 *  최신 여부만 따로 보기 때문이다. */
private fun writeIndexBars(
    dir: File,
    dates: List<String>,
    closes: List<Int>,
) {
    File(dir, "index").mkdirs()
    File(dir, "index/069500.json").writeText(
        """{"dates":[${dates.joinToString(",") { "\"$it\"" }}],"closes":[${closes.joinToString(",")}],"ex":{}}""",
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

    /** iem_cd 로 특정 종목(또는 지수)의 /period 요청 바디를 찾는다 — 동시 처리라 순서가
     *  보장되지 않는 테스트에서 "이 종목이 어떤 조건으로 요청됐는가"를 이름으로 짚어 검증한다. */
    fun periodBody(code: String): String =
        (
            requests
                .first {
                    it.url.encodedPath.endsWith("/period") && "\"iem_cd\":\"$code\"" in (it.body as TextContent).text
                }.body as TextContent
        ).text
}

/** track.json 검증용 표본 신호. 필드값은 사양 §4.1 예시와 같다. */
private fun sampleSignal(
    asOf: String = "20260904",
    targetBp: Int = 2_500,
    band: Band = Band.DEFENSE,
    score: Double = 0.3125,
) = Signal(targetBp = targetBp, band = band, breadth = 0.415, pctile = 0.099, window = 756, asOf = asOf, score = score)

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
    fun `sync은 유니버스 1회와 종목 수만큼 호출하며 진행률을 흘리고 완료로 끝난다`() =
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
            // 지수(069500) 도 종목처럼 한 번 더 받는다 — 캐시가 없어 최초 백필 대상이 된다.
            assertEquals(codes.size + 1, f.periodRequests())
            // 종목은 동시에(최대 4개 lane) 처리되므로 완료 순서는 보장되지 않는다 — 개수·집합으로만 검증한다.
            assertEquals(SyncState.Running(0, 4), states.first(), "total 은 종목 수 + 지수 1 이다")
            assertEquals(SyncState.Done(0), states.last(), "마지막 상태는 항상 완료다")
            assertEquals(6, states.size, "초기 1 + 종목 3 + 지수 1 + 완료 1")
            val doneValues = states.subList(1, states.size - 1).map { (it as SyncState.Running).done }.toSet()
            assertEquals(setOf(1, 2, 3, 4), doneValues, "종목·지수마다 정확히 한 번씩, 1..4 가 모두 나와야 한다")
            codes.forEach { code ->
                assertTrue("\"array_cnt\":\"6\"" in f.periodBody(code), f.periodBody(code)) // 어제까지 있었으니 gap(1)+여유(5)
            }
            assertTrue(
                "\"array_cnt\":\"1100\"" in f.periodBody("069500"),
                "지수는 캐시가 없는 최초 백필이라 종목과 다른 건수를 요청해야 한다: ${f.periodBody("069500")}",
            )
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
            assertEquals(SyncState.Running(0, 3), states.first(), "빈 응답이면 캐시된 2개 목록 + 지수 1로 계속 진행해야 한다")
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
            writeIndexBars(f.dir, listOf(today), listOf(10_000)) // 지수도 오늘까지 있어야 요청이 안 생긴다

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
            writeIndexBars(f.dir, listOf(today), listOf(10_000)) // 지수도 신선해야 손상 종목 하나로 고립된다

            f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else periodResponse(req) }

            f.market.sync().toList()

            assertEquals(1, f.periodRequests(), "신선한 종목·지수는 건너뛰고 손상된 종목만 요청해야 한다")
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
            writeIndexBars(f.dir, listOf(today), listOf(10_000)) // 지수도 신선해야 손상 종목 하나로 고립된다

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
            writeIndexBars(f.dir, listOf(today), listOf(10_000)) // 지수도 신선해야 손상 종목 하나로 고립된다

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
            writeIndexBars(f.dir, listOf(today), listOf(10_000)) // 지수도 신선해야 손상 종목 하나로 고립된다

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
            writeIndexBars(f.dir, listOf(today.format(FMT)), listOf(10_000)) // 지수도 신선해야 종목 하나로 고립된다

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
                    // 이 테스트의 /period 요청은 EXR 갱신과 지수(069500) 최초 백필, 둘이다(baseline 은
                    // 이미 오늘 날짜라 건너뛴다). 이 핸들러는 요청 바디의 array_cnt·edate 만 보고 응답을
                    // 만들므로 어느 쪽이 와도 그대로 처리된다. 새로 받는 구간도 권리락 없이 10000원 그대로다.
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
            writeIndexBars(f.dir, listOf(today.format(FMT)), listOf(10_000)) // 지수도 신선해야 종목 하나로 고립된다

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

    // ---- sync() : 동시 처리(lane) ----

    @Test
    fun `sync은 최대 4개 종목만 동시에 처리한다`() =
        runTest {
            val f = MdFixture()
            f.ready()
            val today = LocalDate.now()
            val codes = (0 until 8).map { "L%06d".format(it) }
            // 다들 어제까지 캐시가 있어 한 페이지(gap+여유)로 끝난다 — lane 수만 재려는 것이지 페이징을 재려는 게 아니다.
            codes.forEach { writeUpStock(f.dir, it, listOf(today.minusDays(1).format(FMT))) }
            writeUniverse(f.dir, at = today.format(FMT), codes = codes)

            val inFlight = AtomicInteger(0)
            val maxInFlight = AtomicInteger(0)
            f.handle = { req ->
                when {
                    req.url.encodedPath == "/oauth2/token" -> {
                        json(TOKEN_BODY)
                    }

                    req.url.encodedPath.endsWith("/period") -> {
                        val cur = inFlight.incrementAndGet()
                        maxInFlight.updateAndGet { prev -> maxOf(prev, cur) }
                        delay(1_000) // 응답을 붙잡아 둬야 동시 in-flight 개수가 겹쳐서 드러난다
                        inFlight.decrementAndGet()
                        periodResponse(req)
                    }

                    else -> {
                        json("{}")
                    }
                }
            }

            f.market.sync().toList()

            assertEquals(4, maxInFlight.get(), "SYNC_LANES(4) 를 넘는 동시 요청이 있으면 안 된다")
            assertEquals(9, f.periodRequests(), "종목 8 + 지수 1")
        }

    @Test
    fun `한 종목의 실패가 다른 종목의 저장을 막지 않는다`() =
        runTest {
            val f = MdFixture()
            f.ready()
            val today = LocalDate.now()
            val codes = (0 until 8).map { "F%06d".format(it) }
            val failCode = codes[3]
            writeUniverse(f.dir, at = today.format(FMT), codes = codes)
            // 전부 캐시가 없다(최초 백필) — lane 하나의 실패가 나머지 lane 을 막지 않아야 한다.

            f.handle = { req ->
                val text = (req.body as? TextContent)?.text.orEmpty()
                when {
                    req.url.encodedPath == "/oauth2/token" -> json(TOKEN_BODY)
                    "\"iem_cd\":\"$failCode\"" in text -> json("""{"rsp_cd":"40010","rsp_msg":"조회 실패"}""")
                    req.url.encodedPath.endsWith("/period") -> periodResponse(req)
                    else -> json("{}")
                }
            }

            val states = f.market.sync().toList()

            assertEquals(SyncState.Done(1), states.last())
            codes.filter { it != failCode }.forEach {
                assertTrue(File(f.dir, "bars/$it.json").exists(), "실패하지 않은 종목 $it 의 파일은 남아야 한다")
            }
            assertFalse(File(f.dir, "bars/$failCode.json").exists())
        }
}

/**
 * [MarketDataTest] 와 같은 파일의 고정구(MdFixture·writeIndexBars·sampleSignal 등)를 그대로
 * 쓰는 별도 클래스다. 069500 지수 동기화·record()·trackData() 테스트까지 한 클래스에 모으면
 * detekt `LargeClass` 에 걸릴 만큼 커져서, 순수 종목 캐시(sync/cached)와 이번 태스크에서
 * 추가된 지수·관측 기록 책임을 클래스 단위로 나눴다.
 */
class MarketDataTrackTest {
    // ---- sync() : 지수 대용(069500) ----

    @Test
    fun `sync은 069500을 index 파일에 BarsFile 형식으로 쓰고 bars에는 남기지 않는다`() =
        runTest {
            val f = MdFixture()
            f.ready()
            val today = LocalDate.now()
            val codes = listOf("111111", "222222")
            val history = dateSeq(400, today.minusDays(400))
            codes.forEach { writeUpStock(f.dir, it, history) }
            writeUniverse(f.dir, at = today.format(FMT), codes = codes)

            f.handle = { req -> if (req.url.encodedPath == "/oauth2/token") json(TOKEN_BODY) else periodResponse(req) }

            val states = f.market.sync().toList()

            assertEquals(SyncState.Running(0, codes.size + 1), states.first(), "total 은 종목 수 + 지수 1 이다")
            val indexJson = File(f.dir, "index/069500.json").readText()
            assertTrue("\"dates\"" in indexJson && "\"closes\"" in indexJson, indexJson)
            assertFalse(File(f.dir, "bars/069500.json").exists(), "지수가 bars/ 에 섞이면 안 된다")
        }

    @Test
    fun `지수 동기화가 실패해도 종목은 저장되고 실패 수에 반영된다`() =
        runTest {
            val f = MdFixture()
            f.ready()
            val today = LocalDate.now().format(FMT)
            val code = "111111"
            writeUniverse(f.dir, at = today, codes = listOf(code))
            writeUpStock(f.dir, code, listOf(today)) // 종목은 이미 최신이라 실패를 지수 하나로 고립한다

            f.handle = { req ->
                val text = (req.body as? TextContent)?.text.orEmpty()
                when {
                    req.url.encodedPath == "/oauth2/token" -> json(TOKEN_BODY)
                    "\"iem_cd\":\"069500\"" in text -> json("""{"rsp_cd":"40010","rsp_msg":"조회 실패"}""")
                    else -> periodResponse(req)
                }
            }

            val states = f.market.sync().toList()

            assertEquals(SyncState.Done(1), states.last(), "지수 실패도 종목처럼 failed 에 센다")
            assertTrue(File(f.dir, "bars/$code.json").exists(), "지수가 실패해도 종목 파일은 그대로 저장돼야 한다")
            assertFalse(File(f.dir, "index/069500.json").exists(), "실패한 지수 요청은 파일을 남기지 않는다")
        }

    @Test
    fun `지수 파일은 cached의 시장폭 종목 수에 섞이지 않는다`() {
        val f = MdFixture()
        val dates = dateSeq(500)
        val baseline = writeBaseline(f.dir, dates) // 전부 above 라 breadth 1.0
        writeUniverse(f.dir, at = "20200101", codes = baseline)
        // 평평한 지수는 above 가 아니다 — 시장폭 종목 수에 잘못 섞이면 30/31 로 떨어져서 드러난다.
        writeIndexBars(f.dir, dates, List(dates.size) { 10_000 })

        val signal = assertNotNull(f.market.cached())
        assertEquals(1.0, signal.breadth, "지수 파일이 시장폭 종목 수에 섞이면 안 된다")
    }

    // ---- record() : 관측 기록 ----

    @Test
    fun `record 첫 쓰기는 기록 파일을 만들고 한 행에 기대한 필드를 담는다`() {
        val f = MdFixture()
        writeUniverse(f.dir, at = "20260905", codes = listOf("A"))

        f.market.record(sampleSignal(), today = "20260906")

        val text = File(f.dir, "track.json").readText()
        assertTrue("\"asOf\":\"20260904\"" in text, text)
        assertTrue("\"computedOn\":\"20260906\"" in text, text)
        assertTrue("\"targetBp\":2500" in text, text)
        assertTrue("\"band\":\"DEFENSE\"" in text, text)
        assertTrue("\"universeAt\":\"20260905\"" in text, text)
        assertTrue("\"universeSize\":1" in text, text)
    }

    @Test
    fun `record은 같은 날 재계산이면 기존 행을 교체한다`() {
        val f = MdFixture()
        f.market.record(sampleSignal(targetBp = 2_500), today = "20260906")

        f.market.record(sampleSignal(targetBp = 5_000), today = "20260906")

        val obs = f.market.trackData().obs
        assertEquals(1, obs.size)
        assertEquals(5_000, obs.first().targetBp)
    }

    @Test
    fun `record은 다른 날 쓰인 행을 절대 바꾸지 않는다`() {
        val f = MdFixture()
        f.market.record(sampleSignal(targetBp = 2_500), today = "20260906")
        val before = File(f.dir, "track.json").readText()

        f.market.record(sampleSignal(targetBp = 9_999), today = "20260913") // 같은 asOf, 다른 날 재계산

        assertEquals(before, File(f.dir, "track.json").readText(), "다른 날 쓰인 행은 바이트 단위로 그대로여야 한다")
    }

    @Test
    fun `record은 같은 asOf 행의 computedOn 이 날짜가 아니면 없는 행으로 보고 교체한다`() {
        val f = MdFixture()
        // 손으로 고쳤거나 옛·새 스키마가 섞여 computedOn 이 날짜가 아닌 행 — 오늘과 절대
        // 같아질 수 없으므로, 없는 행으로 보지 않으면 이 asOf 는 영영 기록되지 않는다.
        File(f.dir, "track.json").writeText(
            """{"rows":[{"asOf":"20260904","computedOn":"garbage","targetBp":9999,"band":"MAX_DEFENSE",""" +
                """"score":0.9,"breadth":0.9,"pctile":0.9,"window":756,"universeAt":"20260905","universeSize":199}]}""",
        )

        f.market.record(sampleSignal(targetBp = 2_500), today = "20260906")

        val obs = f.market.trackData().obs
        assertEquals(1, obs.size)
        assertEquals(2_500, obs.first().targetBp, "computedOn 이 날짜가 아닌 행은 없는 것으로 보고 새 값으로 교체해야 한다")
    }

    @Test
    fun `손상된 기록 파일은 다음 record로 복구된다`() {
        val f = MdFixture()
        File(f.dir, "track.json").writeText("{ 이건 JSON 이 아니다")

        f.market.record(sampleSignal(), today = "20260906")

        val obs = f.market.trackData().obs
        assertEquals(1, obs.size)
        assertEquals("20260904", obs.first().asOf)
    }

    @Test
    fun `유효하지 않은 행은 읽을 때 버려지고 유효한 행만 남는다`() {
        val f = MdFixture()
        File(f.dir, "track.json").writeText(
            """
            {"rows":[
              {"asOf":"20260904","computedOn":"20260906","targetBp":2500,"band":"DEFENSE","score":0.3125,"breadth":0.4,"pctile":0.1,"window":756,"universeAt":"20260905","universeSize":199},
              {"asOf":"garbage","computedOn":"20260906","targetBp":2500,"band":"DEFENSE","score":0.3,"breadth":0.4,"pctile":0.1,"window":756,"universeAt":"20260905","universeSize":199},
              {"asOf":"20260905","computedOn":"20260906","targetBp":20000,"band":"DEFENSE","score":0.3,"breadth":0.4,"pctile":0.1,"window":756,"universeAt":"20260905","universeSize":199},
              {"asOf":"20260907","computedOn":"20260906","targetBp":2500,"band":"DEFENSE","score":1.5,"breadth":0.4,"pctile":0.1,"window":756,"universeAt":"20260905","universeSize":199},
              {"asOf":"20260908","computedOn":"20260906","targetBp":2500,"band":"DEFENSE","score":0.3,"breadth":0.4,"pctile":0.1,"window":-1,"universeAt":"20260905","universeSize":199},
              {"asOf":"20260909","computedOn":"20260906","targetBp":2500,"band":"DEFENSE","score":0.3,"breadth":0.4,"pctile":0.1,"window":756,"universeAt":"","universeSize":0},
              {"asOf":"20260910","computedOn":"20260906","targetBp":2500,"band":"DEFENSE","score":0.3,"breadth":0.4,"pctile":0.1,"window":756,"universeAt":"garbage","universeSize":199}
            ]}
            """.trimIndent(),
        )

        val obs = f.market.trackData().obs

        assertEquals(
            setOf("20260904", "20260909"),
            obs.map { it.asOf }.toSet(),
            "asOf 무효·targetBp 범위 밖·score 범위 밖·window 음수·universeAt 무효 행은 모두 버려져야 한다",
        )
    }

    // ---- trackData() ----

    @Test
    fun `trackData는 지수 파일이 없으면 index가 null이고 나머지 길이는 달력과 같다`() {
        val f = MdFixture()
        val dates = dateSeq(10)
        writeUniverse(f.dir, at = "20200101", codes = listOf("A"))
        writeUpStock(f.dir, "A", dates)

        val data = f.market.trackData()

        assertNull(data.market.index)
        assertEquals(dates.size, data.market.dates.size)
        assertEquals(dates.size, data.retro.size)
        assertEquals(dates.size, data.market.equal.size)
    }

    @Test
    fun `trackData는 지수를 첫 정의일 기준 1로 정규화하고 빈 날은 직전 값을 잇는다`() {
        val f = MdFixture()
        val dates = dateSeq(6)
        writeUniverse(f.dir, at = "20200101", codes = listOf("A"))
        writeUpStock(f.dir, "A", dates)
        // day2·3·5 만 있다 — day4 는 빠져서 직전값 이어짐을 검증한다.
        writeIndexBars(f.dir, listOf(dates[2], dates[3], dates[5]), listOf(200, 220, 240))

        val data = f.market.trackData()

        val idx = assertNotNull(data.market.index)
        assertEquals(dates.size, idx.size)
        assertEquals(dates.size, data.retro.size)
        assertEquals(dates.size, data.market.equal.size)
        assertTrue(idx[0].isNaN(), "첫 정의일 전은 NaN 이어야 한다")
        assertTrue(idx[1].isNaN())
        assertEquals(1.0, idx[2], "첫 정의일은 정확히 1.0")
        assertEquals(220.0 / 200.0, idx[3])
        assertEquals(220.0 / 200.0, idx[4], "지수 파일에 없는 날은 직전 값을 이어간다")
        assertEquals(240.0 / 200.0, idx[5])
    }

    @Test
    fun `trackData는 obs를 기록 파일에서 읽고 universeAt을 채운다`() {
        val f = MdFixture()
        writeUniverse(f.dir, at = "20260905", codes = listOf("A"))
        f.market.record(sampleSignal(asOf = "20260904"), today = "20260906")

        val data = f.market.trackData()

        assertEquals("20260905", data.universeAt)
        assertEquals(1, data.obs.size)
        assertEquals("20260904", data.obs.first().asOf)
        assertEquals(2_500, data.obs.first().targetBp)
    }

    @Test
    fun `trackData는 유니버스가 없어도 obs는 보존하고 나머지는 빈 값이다`() {
        val f = MdFixture()
        f.market.record(sampleSignal(), today = "20260906") // 유니버스 없이도 기록 자체는 된다

        val data = f.market.trackData()

        assertEquals(emptyList<String>(), data.market.dates)
        assertEquals(emptyList<Signal?>(), data.retro)
        assertEquals(0, data.market.equal.size)
        assertNull(data.market.index)
        assertEquals("", data.universeAt)
        assertEquals(1, data.obs.size, "관측 기록은 유니버스와 무관하게 보존된다")
    }
}
