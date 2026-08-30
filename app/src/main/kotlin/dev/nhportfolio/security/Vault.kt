@file:Suppress("MatchingDeclarationName")

package dev.nhportfolio.security

import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
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
 * [seal] 의 역. 키가 틀리거나 변조되면 [javax.crypto.AEADBadTagException] 이 난다.
 * 28바이트(iv+태그) 미만이면 다른 [java.security.GeneralSecurityException] 이 난다 —
 * 그 이상 길이의 변조는 AEAD 특성상 '틀린 키' 와 구분되지 않는다.
 */
fun open(
    key: ByteArray,
    blob: ByteArray,
): ByteArray {
    // iv(12) + 태그(16) 보다 짧으면 seal() 이 만든 blob 이 아니다. 이 검사가 없으면
    // JDK 가 AEADBadTagException 을 던져 '손상' 이 '틀린 PIN' 으로 오분류된다.
    if (blob.size < IV_BYTES + GCM_TAG_BITS / Byte.SIZE_BITS) throw GeneralSecurityException("blob too short")
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

/**
 * 디스크에 봉인되어 저장되는 비밀 전부. 토큰은 메모리 홀더 없이 여기에만 산다 —
 * 잠그면 같은 UID 코드도 읽을 수 없다.
 */
@Serializable
data class Secrets(
    val appKey: String? = null,
    val appSecret: String? = null,
    val token: String? = null,
    val tokenIssuedAt: Long = 0,
    val tokenExpiresAt: Long = 0,
) {
    override fun toString(): String = "Secrets(***)"
}

sealed interface PinResult {
    data object Ok : PinResult

    data class Wrong(
        val remaining: Int,
    ) : PinResult

    data class LockedFor(
        val millis: Long,
    ) : PinResult
}

/** cause 를 붙이지 않는다 — 평문이나 JSON 조각이 스택트레이스에 실리지 않도록. */
class VaultCorruptException : IllegalStateException("secrets corrupt")

private const val PIN_MAC_ALIAS = "nh_pin_mac"
private const val DEK_BYTES = 32
private const val SALT_BYTES = 16

/**
 * 비밀 저장소. 잠금 상태 = 프로세스 메모리의 DEK 유무.
 *
 * 디스크에는 래핑본만 있다: `DEK_PIN = seal(HMAC(pbkdf2(pin, salt)), DEK)`,
 * `SECRETS = seal(DEK, Secrets JSON)`. HMAC 키는 AndroidKeyStore 를 떠나지 못하므로
 * DataStore 파일만 복사해서는 오프라인 대입이 불가능하다.
 *
 * [hmac] · [elapsed] · [bootCount] 는 JVM 테스트를 위한 생성자 훅이다 (인터페이스 없음).
 */
class Vault(
    private val store: DataStore<Preferences>,
    private val hmac: (data: ByteArray, create: Boolean) -> ByteArray? = ::keystoreHmac,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val bootCount: () -> Int,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val pinMutex = Mutex()
    private val unlockedState = MutableStateFlow(false)

    @Volatile
    private var dek: ByteArray? = null

    val unlocked: StateFlow<Boolean> = unlockedState.asStateFlow()

    val hasPin: Flow<Boolean> = store.data.map { K.DEK_PIN in it }.distinctUntilChanged()

    /** 잠기면 빈 [Secrets] 를 낸다 — 구독자(소켓)가 에러 없이 멈춘다. */
    val secretsFlow: Flow<Secrets> =
        unlockedState.flatMapLatest { isUnlocked ->
            if (isUnlocked) {
                store.data.map { prefs -> dek?.let { decodeWith(it, prefs) } ?: Secrets() }
            } else {
                flowOf(Secrets())
            }
        }

    /** 앱 키 저장 여부. 게이트 화면과 설정 화면이 같은 판정을 공유한다. */
    val hasKeys: Flow<Boolean> = secretsFlow.map { it.appKey != null }.distinctUntilChanged()

    suspend fun secrets(): Secrets = decode(store.data.first())

    suspend fun update(transform: (Secrets) -> Secrets) {
        val key = dek ?: error("locked")
        // ponytail: lock() 이 transform 도중 DEK 를 0으로 덮으면 SECRETS 가 못 여는 blob 이 된다(마이크로초 창).
        // 실측되면 lock() 을 suspend 로 바꿔 pinMutex 로 감싼다.
        store.edit { prefs ->
            val next = transform(decodeWith(key, prefs))
            prefs[K.SECRETS] = seal(key, json.encodeToString(next).toByteArray()).b64()
        }
    }

    /**
     * 최초 설정이면 새 DEK·솔트·Keystore 키를 만들고, 이후에는 잠금이 풀린 상태에서
     * 같은 DEK 를 새 PIN 으로 다시 래핑한다. [pin] 은 반환 전에 지워진다.
     */
    @Suppress("ThrowsCount")
    suspend fun setPin(pin: CharArray) {
        try {
            require(!weakPin(pin)) { "너무 단순한 PIN 입니다" }
            val prefs = store.data.first()
            val isFirst = K.DEK_PIN !in prefs
            val newDek = if (isFirst) randomBytes(DEK_BYTES) else (dek ?: error("locked"))
            val salt =
                if (isFirst) randomBytes(SALT_BYTES) else (prefs[K.SALT] ?: throw VaultCorruptException()).unb64()

            val kek =
                withContext(Dispatchers.Default) {
                    val derived = pbkdf2(pin, salt, PBKDF2_ITERS)
                    try {
                        hmac(derived, isFirst)
                    } finally {
                        derived.fill(0)
                    }
                } ?: throw VaultCorruptException()
            try {
                store.edit {
                    it[K.SALT] = salt.b64()
                    it[K.PBKDF2_ITERS] = PBKDF2_ITERS
                    it[K.DEK_PIN] = seal(kek, newDek).b64()
                    it.remove(K.FAILS)
                    it.remove(K.LOCK_ELAPSED)
                    it.remove(K.LOCK_BOOT)
                }
            } catch (e: Exception) {
                // PIN 변경(비-최초) 경로에서는 newDek 가 살아있는 dek 와 같은 배열이다 — 지우면
                // 방금 실패한 시도가 아니라 현재 세션을 깨뜨린다. 최초 설정에서 만든, 아직 아무도
                // 참조하지 않는 새 배열일 때만 안전하게 지운다.
                if (isFirst) newDek.fill(0)
                throw e
            } finally {
                kek.fill(0)
            }
            dek = newDek
            unlockedState.value = true
        } finally {
            pin.fill('0')
        }
    }

    /**
     * PIN 검증 = DEK_PIN 의 GCM 태그 검사. 해시를 저장하거나 비교하는 코드는 없다.
     * 실패 횟수를 **검증 전에** 기록한다 (쓰기가 실패하면 시도 자체가 중단된다).
     * 시계는 단조 시계 + BOOT_COUNT 뿐이라 시간 설정을 바꿔 잠금을 줄일 수 없다.
     */
    suspend fun unlockWithPin(pin: CharArray): PinResult =
        try {
            pinMutex.withLock {
                val initial = store.data.first()
                val prefs =
                    if (bootCount() != initial[K.LOCK_BOOT]) {
                        store.edit {
                            it[K.LOCK_ELAPSED] = elapsed() + lockoutMillis(it[K.FAILS] ?: 0)
                            it[K.LOCK_BOOT] = bootCount()
                        }
                    } else {
                        initial
                    }

                val lockedUntil = prefs[K.LOCK_ELAPSED] ?: 0L
                if (elapsed() < lockedUntil) return@withLock PinResult.LockedFor(lockedUntil - elapsed())

                val salt = prefs[K.SALT] ?: throw VaultCorruptException()
                val iterations = prefs[K.PBKDF2_ITERS] ?: throw VaultCorruptException()
                val wrapped = prefs[K.DEK_PIN] ?: throw VaultCorruptException()
                val saltBytes: ByteArray
                val wrappedBytes: ByteArray
                try {
                    saltBytes = salt.unb64()
                    wrappedBytes = wrapped.unb64()
                } catch (e: IllegalArgumentException) {
                    throw VaultCorruptException()
                }

                val fails =
                    store.edit {
                        val next = (it[K.FAILS] ?: 0) + 1
                        it[K.FAILS] = next
                        it[K.LOCK_ELAPSED] = elapsed() + lockoutMillis(next)
                    }[K.FAILS] ?: 1

                val opened =
                    withContext(Dispatchers.Default) {
                        val derived = pbkdf2(pin, saltBytes, iterations)
                        val kek =
                            (
                                try {
                                    hmac(derived, false)
                                } finally {
                                    derived.fill(0)
                                }
                            )
                                ?: throw VaultCorruptException()
                        try {
                            unwrap(kek, wrappedBytes)
                        } finally {
                            kek.fill(0)
                        }
                    }
                if (opened == null) return@withLock PinResult.Wrong(maxOf(0, MAX_FREE_TRIES - fails))

                try {
                    store.edit {
                        it.remove(K.FAILS)
                        it.remove(K.LOCK_ELAPSED)
                        it.remove(K.LOCK_BOOT)
                    }
                } catch (e: Exception) {
                    opened.fill(0)
                    throw e
                }
                dek = opened
                unlockedState.value = true
                PinResult.Ok
            }
        } finally {
            pin.fill('0')
        }

    fun lock() {
        val current = dek
        dek = null
        unlockedState.value = false
        current?.fill(0)
    }

    /**
     * 자격증명·토큰·목표 비중·PIN 을 전부 지운다. Keystore 키는 건드리지 않는다 —
     * 래핑본이 없는 키는 무용지물이고, [setPin] 과 지문 등록이 같은 별칭으로 덮어쓴다.
     */
    suspend fun wipe() {
        lock()
        store.edit { it.clear() }
    }

    internal fun dek(): ByteArray? = dek

    internal fun unlockWith(newDek: ByteArray) {
        dek = newDek
        unlockedState.value = true
    }

    private fun decode(prefs: Preferences): Secrets = decodeWith(dek ?: error("locked"), prefs)

    @Suppress("SwallowedException", "ThrowsCount")
    private fun decodeWith(
        key: ByteArray,
        prefs: Preferences,
    ): Secrets {
        val blob = prefs[K.SECRETS] ?: return Secrets()
        return try {
            json.decodeFromString(String(open(key, blob.unb64()), Charsets.UTF_8))
        } catch (e: GeneralSecurityException) {
            throw VaultCorruptException()
        } catch (e: SerializationException) {
            throw VaultCorruptException()
        } catch (e: IllegalArgumentException) {
            // Base64 디코드 실패
            throw VaultCorruptException()
        }
    }

    @Suppress("SwallowedException")
    private fun unwrap(
        kek: ByteArray,
        wrapped: ByteArray,
    ): ByteArray? =
        try {
            open(kek, wrapped)
        } catch (e: AEADBadTagException) {
            null // 태그 불일치만 "틀린 PIN"
        } catch (e: GeneralSecurityException) {
            throw VaultCorruptException() // 잘린 blob 등은 손상
        }
}

private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }

/**
 * AndroidKeyStore 의 HMAC 키로 서명한다. [create] 가 true 면 키를 새로 만들고(덮어쓰기),
 * false 인데 키가 없으면 null 을 돌려준다 — 키 소실이 "틀린 PIN" 으로 위장되지 않도록.
 */
private fun keystoreHmac(
    data: ByteArray,
    create: Boolean,
): ByteArray? {
    val key =
        if (create) {
            generatePinMacKey()
        } else {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            keyStore.getKey(PIN_MAC_ALIAS, null) as? SecretKey ?: return null
        }
    return Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(data)
}

@Suppress("SwallowedException")
private fun generatePinMacKey(): SecretKey {
    fun spec(strongBox: Boolean) =
        KeyGenParameterSpec
            .Builder(PIN_MAC_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setUnlockedDeviceRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()

    val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
    return try {
        generator.init(spec(true))
        generator.generateKey()
    } catch (e: ProviderException) {
        // StrongBoxUnavailableException 은 ProviderException 의 하위 타입
        generator.init(spec(false))
        generator.generateKey()
    }
}
