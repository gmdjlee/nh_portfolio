package dev.nhportfolio

import dev.nhportfolio.security.lockoutMillis
import dev.nhportfolio.security.open
import dev.nhportfolio.security.pbkdf2
import dev.nhportfolio.security.seal
import dev.nhportfolio.security.weakPin
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun bytes(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }

class CryptoTest {
    @Test
    fun `봉인한 것을 다시 연다`() {
        val key = bytes(32)
        val plain = "삼성전자 005930".toByteArray()
        assertContentEquals(plain, open(key, seal(key, plain)))
    }

    @Test
    fun `같은 평문을 두 번 봉인하면 결과가 다르다`() {
        val key = bytes(32)
        assertFalse(seal(key, "x".toByteArray()).contentEquals(seal(key, "x".toByteArray())))
    }

    @Test
    fun `변조된 blob 은 열리지 않는다`() {
        val key = bytes(32)
        val blob = seal(key, "secret".toByteArray())
        blob[blob.size - 1] = (blob[blob.size - 1] + 1).toByte()
        assertFailsWith<AEADBadTagException> { open(key, blob) }
    }

    @Test
    fun `다른 키로는 열리지 않는다`() {
        val blob = seal(bytes(32), "secret".toByteArray())
        assertFailsWith<AEADBadTagException> { open(bytes(32), blob) }
    }

    @Test
    fun `pbkdf2 는 결정적이고 솔트와 반복수에 반응한다`() {
        val salt = bytes(16)
        val a = pbkdf2("123456".toCharArray(), salt, 1_000)
        assertContentEquals(a, pbkdf2("123456".toCharArray(), salt, 1_000))
        assertEquals(32, a.size)
        assertFalse(a.contentEquals(pbkdf2("123456".toCharArray(), bytes(16), 1_000)))
        assertFalse(a.contentEquals(pbkdf2("123456".toCharArray(), salt, 2_000)))
        assertFalse(a.contentEquals(pbkdf2("654321".toCharArray(), salt, 1_000)))
    }

    @Test
    fun `잠금 시간은 5회부터 두 배씩 늘고 1시간에서 멈춘다`() {
        val table =
            listOf(
                0 to 0L,
                4 to 0L,
                5 to 30_000L,
                6 to 60_000L,
                7 to 120_000L,
                8 to 240_000L,
                9 to 480_000L,
                10 to 960_000L,
                11 to 1_920_000L,
                12 to 3_600_000L,
                20 to 3_600_000L,
                54 to 3_600_000L,
                69 to 3_600_000L,
                1_000 to 3_600_000L,
            )
        for ((fails, expected) in table) assertEquals(expected, lockoutMillis(fails), "fails=$fails")
    }

    @Test
    fun `단순한 PIN 은 거부하고 나머지는 허용한다`() {
        for (weak in listOf("000000", "111111", "123456", "654321", "345678")) {
            assertTrue(weakPin(weak.toCharArray()), weak)
        }
        for (ok in listOf("135790", "112233", "192837", "100000")) {
            assertFalse(weakPin(ok.toCharArray()), ok)
        }
    }

    @Test
    fun `짧은 blob 은 태그 불일치가 아니라 손상이다`() {
        val key = bytes(32)
        val short = seal(key, "x".toByteArray()).copyOf(20) // iv(12) + 태그(16) 미만
        val e = assertFailsWith<GeneralSecurityException> { open(key, short) }
        assertFalse(e is AEADBadTagException, "짧은 blob 은 '틀린 키' 로 오분류되면 안 된다")
    }
}
