package dev.nhportfolio

import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import dev.nhportfolio.security.K
import dev.nhportfolio.security.PBKDF2_ITERS
import dev.nhportfolio.security.PinResult
import dev.nhportfolio.security.Secrets
import dev.nhportfolio.security.Vault
import dev.nhportfolio.security.VaultCorruptException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val GOOD_PIN = "135790"
private const val OTHER_PIN = "192837"

private class Fixture {
    var now: Long = 1_000L
    var boot: Int = 7
    var hmacThrows = false
    var hmacMissing = false
    val createFlags = mutableListOf<Boolean>()

    private val dir: File = Files.createTempDirectory("vault").toFile()
    private val macKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "HmacSHA256")

    val store =
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ) { File(dir, "vault.preferences_pb") }

    val vault =
        Vault(
            store = store,
            hmac = { data, create ->
                createFlags += create
                when {
                    hmacThrows -> error("keystore unavailable")
                    hmacMissing -> null
                    else -> Mac.getInstance("HmacSHA256").apply { init(macKey) }.doFinal(data)
                }
            },
            elapsed = { now },
            bootCount = { boot },
        )

    suspend fun prefs() = store.data.first()
}

class VaultTest {
    @Test
    fun `PIN 을 설정하면 잠금이 풀리고 같은 PIN 으로 다시 열린다`() =
        runTest {
            val f = Fixture()
            f.vault.setPin(GOOD_PIN.toCharArray())
            assertTrue(f.vault.unlocked.value)
            assertTrue(f.vault.hasPin.first())

            f.vault.update { it.copy(appKey = "KEY", appSecret = "SECRET") }
            f.vault.lock()
            assertFalse(f.vault.unlocked.value)

            assertEquals(PinResult.Ok, f.vault.unlockWithPin(GOOD_PIN.toCharArray()))
            assertEquals("KEY", f.vault.secrets().appKey)
        }

    @Test
    fun `약한 PIN 은 거부한다`() =
        runTest {
            val f = Fixture()
            assertFailsWith<IllegalArgumentException> { f.vault.setPin("123456".toCharArray()) }
        }

    @Test
    fun `틀린 PIN 은 남은 시도 횟수를 알려준다`() =
        runTest {
            val f = Fixture()
            f.vault.setPin(GOOD_PIN.toCharArray())
            f.vault.lock()
            assertEquals(PinResult.Wrong(4), f.vault.unlockWithPin(OTHER_PIN.toCharArray()))
            assertEquals(PinResult.Wrong(3), f.vault.unlockWithPin(OTHER_PIN.toCharArray()))
            assertFalse(f.vault.unlocked.value)
        }

    @Test
    fun `5회 실패하면 잠기고 시간이 지나야 풀린다`() =
        runTest {
            val f = Fixture()
            f.vault.setPin(GOOD_PIN.toCharArray())
            f.vault.lock()
            repeat(5) { f.vault.unlockWithPin(OTHER_PIN.toCharArray()) }

            val locked = f.vault.unlockWithPin(GOOD_PIN.toCharArray())
            assertTrue(locked is PinResult.LockedFor && locked.millis == 30_000L, "locked=$locked")

            f.now += 30_000
            assertEquals(PinResult.Ok, f.vault.unlockWithPin(GOOD_PIN.toCharArray()))
            assertNull(f.prefs()[K.FAILS])
            assertNull(f.prefs()[K.LOCK_ELAPSED])
        }

    @Test
    fun `재부팅해도 잠금이 다시 무장된다`() =
        runTest {
            val f = Fixture()
            f.vault.setPin(GOOD_PIN.toCharArray())
            f.vault.lock()
            repeat(5) { f.vault.unlockWithPin(OTHER_PIN.toCharArray()) }

            f.boot = 8 // 재부팅
            f.now = 0 // elapsedRealtime 은 0 부터 다시 센다
            assertTrue(f.vault.unlockWithPin(GOOD_PIN.toCharArray()) is PinResult.LockedFor, "재부팅으로 잠금을 우회할 수 없어야 한다")

            f.now = 30_000
            assertEquals(PinResult.Ok, f.vault.unlockWithPin(GOOD_PIN.toCharArray()))
        }

    @Test
    fun `재부팅 직후 첫 시도가 가짜 잠금이 되지 않는다`() =
        runTest {
            val f = Fixture()
            f.vault.setPin(GOOD_PIN.toCharArray())
            f.vault.lock()
            f.boot = 9
            f.now = 0
            assertEquals(PinResult.Ok, f.vault.unlockWithPin(GOOD_PIN.toCharArray()))
        }

    @Test
    fun `동시 시도가 실패 횟수를 잃어버리지 않는다`() =
        runTest {
            val f = Fixture()
            f.vault.setPin(GOOD_PIN.toCharArray())
            f.vault.lock()
            listOf(
                async(Dispatchers.Default) { f.vault.unlockWithPin(OTHER_PIN.toCharArray()) },
                async(Dispatchers.Default) { f.vault.unlockWithPin(OTHER_PIN.toCharArray()) },
            ).awaitAll()
            assertEquals(2, f.prefs()[K.FAILS])
        }

    @Test
    fun `Keystore 가 죽어도 실패 횟수는 이미 기록되어 있다`() =
        runTest {
            val f = Fixture()
            f.vault.setPin(GOOD_PIN.toCharArray())
            f.vault.lock()
            f.hmacThrows = true
            val e = assertFailsWith<IllegalStateException> { f.vault.unlockWithPin(GOOD_PIN.toCharArray()) }
            assertFalse(e is VaultCorruptException, "잠김은 손상이 아니다")
            assertEquals(1, f.prefs()[K.FAILS])
        }

    @Test
    fun `Keystore 키 소실은 틀린 PIN 이 아니라 손상이다`() =
        runTest {
            val f = Fixture()
            f.vault.setPin(GOOD_PIN.toCharArray())
            f.vault.lock()
            f.hmacMissing = true
            assertFailsWith<VaultCorruptException> { f.vault.unlockWithPin(GOOD_PIN.toCharArray()) }
        }

    @Test
    fun `잘린 blob 과 솔트 부재도 손상이다`() =
        runTest {
            val f = Fixture()
            f.vault.setPin(GOOD_PIN.toCharArray())
            f.vault.lock()
            f.store.edit { it[K.DEK_PIN] = "AAAAAAAAAAAAAAAA" }
            assertFailsWith<VaultCorruptException> { f.vault.unlockWithPin(GOOD_PIN.toCharArray()) }

            f.store.edit { it.remove(K.SALT) }
            assertFailsWith<VaultCorruptException> { f.vault.unlockWithPin(GOOD_PIN.toCharArray()) }
        }

    @Test
    fun `변조된 비밀 blob 은 손상으로 보고한다`() =
        runTest {
            val f = Fixture()
            f.vault.setPin(GOOD_PIN.toCharArray())
            f.vault.update { it.copy(appKey = "KEY") }
            val blob = f.prefs()[K.SECRETS]!!
            f.store.edit { it[K.SECRETS] = blob.dropLast(4) + "AAAA" }
            assertFailsWith<VaultCorruptException> { f.vault.secrets() }
        }

    @Test
    fun `잠기면 비밀을 읽을 수 없고 흐름은 빈 값을 낸다`() =
        runTest {
            val f = Fixture()
            f.vault.setPin(GOOD_PIN.toCharArray())
            f.vault.update { it.copy(appKey = "KEY", token = "T") }
            f.vault.lock()
            val e = assertFailsWith<IllegalStateException> { f.vault.secrets() }
            assertFalse(e is VaultCorruptException, "잠김은 손상이 아니다")
            assertEquals(Secrets(), f.vault.secretsFlow.first())
        }

    @Test
    fun `초기화하면 잠기고 PIN 도 사라지며 옛 blob 은 열리지 않는다`() =
        runTest {
            val f = Fixture()
            f.vault.setPin(GOOD_PIN.toCharArray())
            f.vault.update { it.copy(appKey = "KEY") }
            val oldBlob = f.prefs()[K.SECRETS]!!

            f.vault.wipe()
            assertFalse(f.vault.unlocked.value)
            assertFalse(f.vault.hasPin.first())

            f.vault.setPin(GOOD_PIN.toCharArray())
            f.store.edit { it[K.SECRETS] = oldBlob }
            assertFailsWith<VaultCorruptException> { f.vault.secrets() }
        }

    @Test
    fun `입력한 PIN 배열은 지워진다`() =
        runTest {
            val f = Fixture()
            val pin = GOOD_PIN.toCharArray()
            f.vault.setPin(pin)
            assertTrue(pin.all { it == '0' }, "setPin 이 PIN 배열을 지워야 한다")

            f.vault.lock()
            val pin2 = GOOD_PIN.toCharArray()
            f.vault.unlockWithPin(pin2)
            assertTrue(pin2.all { it == '0' }, "unlockWithPin 이 PIN 배열을 지워야 한다")
        }

    @Test
    fun `PBKDF2 반복수는 저장되고 해제할 때 그 값을 쓴다`() =
        runTest {
            val f = Fixture()
            f.vault.setPin(GOOD_PIN.toCharArray())
            assertEquals(PBKDF2_ITERS, f.prefs()[K.PBKDF2_ITERS])

            f.vault.lock()
            f.store.edit { it[K.PBKDF2_ITERS] = PBKDF2_ITERS + 1 }
            // 저장값이 달라지면 KEK 도 달라져 같은 PIN 이 틀린 것으로 판정된다 = 저장값을 쓴다는 증거
            assertTrue(f.vault.unlockWithPin(GOOD_PIN.toCharArray()) is PinResult.Wrong)
        }

    @Test
    fun `PIN 변경은 잠금이 풀린 상태에서만 되고 같은 비밀을 유지한다`() =
        runTest {
            val f = Fixture()
            f.vault.setPin(GOOD_PIN.toCharArray())
            f.vault.update { it.copy(appKey = "KEY") }

            f.vault.setPin(OTHER_PIN.toCharArray())
            f.vault.lock()
            assertEquals(PinResult.Ok, f.vault.unlockWithPin(OTHER_PIN.toCharArray()))
            assertEquals("KEY", f.vault.secrets().appKey)

            f.vault.lock()
            val e = assertFailsWith<IllegalStateException> { f.vault.setPin(GOOD_PIN.toCharArray()) }
            assertFalse(e is VaultCorruptException, "잠김은 손상이 아니다")
        }

    @Test
    fun `PIN 변경은 Keystore 키를 다시 만들지 않는다`() =
        runTest {
            val f = Fixture()
            f.vault.setPin(GOOD_PIN.toCharArray())
            assertEquals(listOf(true), f.createFlags, "최초 설정은 키를 생성한다")
            f.vault.setPin(OTHER_PIN.toCharArray())
            assertEquals(listOf(true, false), f.createFlags, "PIN 변경은 기존 키를 재사용해야 한다")
        }
}
