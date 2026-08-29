@file:Suppress("MatchingDeclarationName")

package dev.nhportfolio.security

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** DataStore Preferences 키. 바이트 blob 은 Base64 문자열로 저장한다. */
object K {
    /** DEK 로 봉인된 [Secrets] JSON */
    val SECRETS = stringPreferencesKey("secrets")

    /** PIN 유래 KEK 로 봉인된 DEK */
    val DEK_PIN = stringPreferencesKey("dek_pin")

    /** 생체 인증 Keystore 키로 암호화된 DEK */
    val DEK_BIO = stringPreferencesKey("dek_bio")

    val SALT = stringPreferencesKey("salt")
    val PBKDF2_ITERS = intPreferencesKey("pbkdf2_iters")
    val FAILS = intPreferencesKey("fails")

    /** 잠금 해제가 가능해지는 elapsedRealtime 시각 */
    val LOCK_ELAPSED = longPreferencesKey("lock_elapsed")

    /** LOCK_ELAPSED 를 기록할 때의 BOOT_COUNT — 재부팅을 감지해 잠금을 다시 무장한다 */
    val LOCK_BOOT = intPreferencesKey("lock_boot")
}

/**
 * PIN 스트레칭 반복수. 보안 상한이 아니다 — salt 와 반복수가 파일에 있으므로
 * 10^6 PIN 공간은 오프디바이스로 미리 계산할 수 있다. 진짜 상한은 하드웨어를
 * 떠나지 못하는 Keystore HMAC 키다. 여기서는 형식적 스트레칭만 한다.
 * 값은 setPin 시점에 [K.PBKDF2_ITERS] 로 저장되고 해제할 때는 저장값을 쓴다.
 */
const val PBKDF2_ITERS: Int = 10_000

private const val GCM_TAG_BITS = 128
private const val IV_BYTES = 12
private const val KEY_BITS = 256
private const val MAX_FREE_TRIES = 5
private const val LOCKOUT_BASE_MS = 30_000L
private const val LOCKOUT_MAX_MS = 3_600_000L
private const val LOCKOUT_MAX_SHIFT = 7

/** AES-256-GCM 봉인. 결과는 `iv(12) || ciphertext+tag`. */
fun seal(
    key: ByteArray,
    plain: ByteArray,
): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
    return cipher.iv + cipher.doFinal(plain)
}

/**
 * [seal] 의 역. 키가 틀리거나 변조되면 [javax.crypto.AEADBadTagException],
 * blob 이 잘렸으면 다른 [java.security.GeneralSecurityException] 이 난다.
 */
fun open(
    key: ByteArray,
    blob: ByteArray,
): ByteArray {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, blob, 0, IV_BYTES))
    return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
}

/** PIN -> 32바이트. 호출자가 [pin] 을 지운다. */
fun pbkdf2(
    pin: CharArray,
    salt: ByteArray,
    iterations: Int,
): ByteArray {
    val spec = PBEKeySpec(pin, salt, iterations, KEY_BITS)
    try {
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

/**
 * 실패 [fails] 회일 때 잠금 시간(ms). 5회부터 30초에서 두 배씩, 상한 1시간.
 *
 * `shl` 은 Long 에서 하위 6비트만 쓰므로 시프트 폭을 반드시 포화시켜야 한다
 * (그러지 않으면 54회부터 음수가 되어 잠금이 사라진다).
 */
fun lockoutMillis(fails: Int): Long =
    if (fails < MAX_FREE_TRIES) {
        0L
    } else {
        (LOCKOUT_BASE_MS shl minOf(fails - MAX_FREE_TRIES, LOCKOUT_MAX_SHIFT)).coerceAtMost(LOCKOUT_MAX_MS)
    }

/** 모든 자리가 같거나 1씩 오르내리는 PIN(000000·123456·654321 등)을 거부한다. */
fun weakPin(pin: CharArray): Boolean {
    val deltas = pin.map { it - '0' }.zipWithNext { a, b -> b - a }.toSet()
    return deltas.size == 1 && deltas.first() in -1..1
}

internal fun ByteArray.b64(): String = Base64.getEncoder().encodeToString(this)

internal fun String.unb64(): ByteArray = Base64.getDecoder().decode(this)

internal operator fun Preferences.contains(key: Preferences.Key<*>): Boolean = key in asMap().keys
