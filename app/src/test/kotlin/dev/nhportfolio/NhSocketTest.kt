package dev.nhportfolio

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import dev.nhportfolio.api.NhApi
import dev.nhportfolio.model.Fill
import dev.nhportfolio.security.Vault
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val D2 = """
{"header":{"tr_cd":"d2","tr_key":""},
 "body":{"userid":"ID","itemgb":"1","accountno":"20101036881","orderno":"30","issuecd":"005940",
         "slbygb":"2","concgty":"0000000005","concprc":"00000035550","conctime":"115606",
         "issue_nm":"NH투자증권"}}
"""

/** 구독 ack — d2 이지만 체결 필드가 없다. 재조회 트리거가 되면 안 된다. */
private const val ACK = """{"header":{"tr_cd":"d2"},"body":{"rsp_cd":"00000","rsp_msg":"정상"}}"""

private const val D3 = """{"header":{"tr_cd":"d3"},"body":{"accountno":"1","orderno":"2"}}"""

private const val BAD_QTY = """
{"header":{"tr_cd":"d2"},"body":{"accountno":"1","concgty":"","concprc":"100","conctime":"090000"}}
"""

private class Socket {
    private val dir: File = Files.createTempDirectory("ws").toFile()
    private val macKey = SecretKeySpec(ByteArray(32) { 3 }, "HmacSHA256")

    val store =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ) { File(dir, "ws.preferences_pb") }

    val vault =
        Vault(
            store = store,
            hmac = { data, _ -> Mac.getInstance("HmacSHA256").apply { init(macKey) }.doFinal(data) },
            elapsed = { 0L },
            bootCount = { 1 },
        )
    val api = NhApi(vault)

    /** 클라이언트가 보낸 구독 프레임 원문. */
    val subscribes = CopyOnWriteArrayList<String>()

    /** 연결 횟수. */
    @Volatile var connections = 0

    private var server: io.ktor.server.engine.EmbeddedServer<*, *>? = null

    /** [onSubscribed] 는 구독 프레임을 받은 뒤 서버가 할 일. 반환하면 서버가 세션을 닫는다. */
    fun start(onSubscribed: suspend io.ktor.server.websocket.DefaultWebSocketServerSession.() -> Unit): String {
        val s =
            embeddedServer(CIO, port = 0) {
                install(WebSockets)
                routing {
                    webSocket("/websocket") {
                        connections++
                        subscribes += (incoming.receive() as Frame.Text).readText()
                        onSubscribed()
                    }
                }
            }.start(wait = false)
        server = s
        val port =
            runBlocking {
                s.engine
                    .resolvedConnectors()
                    .first()
                    .port
            }
        return "ws://127.0.0.1:$port/websocket"
    }

    suspend fun ready(token: String? = "TOKEN") {
        vault.setPin("135790".toCharArray())
        vault.update {
            it.copy(appKey = "K", appSecret = "S", token = token, tokenExpiresAt = Long.MAX_VALUE, tokenIssuedAt = 1)
        }
    }

    @AfterTest
    fun stop() {
        server?.stop(0, 0)
    }
}

private suspend fun await(
    timeoutMs: Long = 8_000,
    condition: () -> Boolean,
) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!condition() && System.currentTimeMillis() < deadline) delay(25)
    assertTrue(condition(), "조건이 ${timeoutMs}ms 안에 충족되지 않았다")
}

private fun CoroutineScope.collectFills(
    api: NhApi,
    url: String,
    into: MutableList<Fill>,
): Job = launch(Dispatchers.Default) { api.fillsFrom(url).collect { into += it } }

class NhSocketTest {
    @Test
    fun `구독 프레임은 명세 그대로다`() =
        runBlocking {
            val s = Socket()
            val url = s.start { delay(3_000) }
            s.ready()
            val fills = CopyOnWriteArrayList<Fill>()
            val job = collectFills(s.api, url, fills)

            withTimeout(10_000) { await { s.subscribes.isNotEmpty() } }
            job.cancel()
            s.stop()

            val expected =
                Json.parseToJsonElement(
                    """{"header":{"token":"TOKEN","tr_type":"1"},"body":{"tr_cd":"d2","tr_key":""}}""",
                )
            assertEquals(expected, Json.parseToJsonElement(s.subscribes.first()))
        }

    @Test
    fun `d2 체결 프레임을 Fill 로 바꾼다`() =
        runBlocking {
            val s = Socket()
            val url =
                s.start {
                    send(Frame.Text(D2))
                    delay(3_000)
                }
            s.ready()
            val fills = CopyOnWriteArrayList<Fill>()
            val job = collectFills(s.api, url, fills)

            withTimeout(10_000) { await { fills.isNotEmpty() } }
            job.cancel()
            s.stop()

            val fill = fills.first()
            assertEquals("20101036881", fill.acctNo)
            assertEquals("NH투자증권", fill.name)
            assertEquals(5, fill.qty)
            assertEquals(35_550, fill.price)
            assertEquals("115606", fill.time)
        }

    @Test
    fun `ack 와 다른 채널과 깨진 수량은 무시하고 연결을 유지한다`() =
        runBlocking {
            val s = Socket()
            val url =
                s.start {
                    send(Frame.Text(ACK))
                    send(Frame.Text(D3))
                    send(Frame.Text(BAD_QTY))
                    send(Frame.Text("not json"))
                    delay(300)
                    send(Frame.Text(D2))
                    delay(3_000)
                }
            s.ready()
            val fills = CopyOnWriteArrayList<Fill>()
            val job = collectFills(s.api, url, fills)

            withTimeout(10_000) { await { fills.isNotEmpty() } }
            job.cancel()
            s.stop()

            assertEquals(1, fills.size, "체결이 아닌 프레임은 Fill 이 되면 안 된다")
            assertEquals(1, s.connections, "무시한 프레임 때문에 재연결하면 안 된다")
        }

    @Test
    fun `서버가 정상 종료해도 다시 연결한다`() =
        runBlocking {
            val s = Socket()
            val url =
                s.start {
                    send(Frame.Text(D2))
                    close(CloseReason(CloseReason.Codes.NORMAL, "bye"))
                }
            s.ready()
            val fills = CopyOnWriteArrayList<Fill>()
            val job = collectFills(s.api, url, fills)

            withTimeout(15_000) { await(12_000) { s.connections >= 2 } }
            job.cancel()
            s.stop()
        }

    @Test
    fun `토큰이 바뀌면 새 토큰으로 다시 구독한다`() =
        runBlocking {
            val s = Socket()
            val url = s.start { delay(10_000) }
            s.ready()
            val fills = CopyOnWriteArrayList<Fill>()
            val job = collectFills(s.api, url, fills)

            withTimeout(10_000) { await { s.subscribes.size == 1 } }
            s.vault.update { it.copy(token = "TOKEN2") }
            withTimeout(10_000) { await { s.subscribes.size == 2 } }
            job.cancel()
            s.stop()

            assertTrue("TOKEN2" in s.subscribes[1])
        }

    @Test
    fun `잠그면 세션이 끊기고 다시 열면 재구독한다`() =
        runBlocking {
            val s = Socket()
            val url = s.start { delay(10_000) }
            s.ready()
            val fills = CopyOnWriteArrayList<Fill>()
            val job = collectFills(s.api, url, fills)

            withTimeout(10_000) { await { s.subscribes.size == 1 } }
            s.vault.lock()
            delay(1_500)
            assertEquals(1, s.subscribes.size, "잠긴 동안 재연결하면 안 된다")

            s.vault.unlockWithPin("135790".toCharArray())
            withTimeout(10_000) { await { s.subscribes.size == 2 } }
            job.cancel()
            s.stop()
        }

    @Test
    fun `토큰이 없으면 아예 연결하지 않는다`() =
        runBlocking {
            val s = Socket()
            val url = s.start { delay(3_000) }
            s.ready(token = null)
            val fills = CopyOnWriteArrayList<Fill>()
            val job = collectFills(s.api, url, fills)

            delay(1_500)
            job.cancel()
            s.stop()

            assertEquals(0, s.connections)
        }

    @Test
    fun `백오프는 1초에서 두 배씩 늘고 30초에서 멈춘다`() {
        val expected = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L)
        expected.forEachIndexed { streak, ms -> assertEquals(ms, NhApi.backoffMs(streak), "streak=$streak") }
        assertEquals(30_000L, NhApi.backoffMs(1_000))
    }
}
