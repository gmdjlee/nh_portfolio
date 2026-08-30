package dev.nhportfolio.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

private const val BIO_ALIAS = "nh_bio"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val BIO_TAG_BITS = 128
private const val BIO_IV_BYTES = 12
private const val BIO_KEY_BITS = 256

/**
 * 지문으로 DEK 를 여는 경로. 우회가 불가능한 이유는 CryptoObject 의 **출력이 곧 DEK** 이기 때문이다 —
 * 인증 성공 여부(boolean)를 믿는 구조가 아니다.
 *
 * 이 파일 하나를 지우면 지문 기능이 통째로 사라진다. PIN 경로는 영향받지 않는다.
 */
class Biometric(
    private val store: DataStore<Preferences>,
    private val vault: Vault,
) {
    val enrolled: Flow<Boolean> = store.data.map { K.DEK_BIO in it }

    /** 잠금이 풀린 상태에서만 호출한다(UI 는 PIN 재검증 후 호출). 실패해도 예외 대신 false. */
    suspend fun enroll(activity: FragmentActivity): Boolean =
        runCatching { enrollInner(activity) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(false)

    private suspend fun enrollInner(activity: FragmentActivity): Boolean {
        val dek = vault.dek() ?: return false
        val cipher =
            runCatching {
                // 키를 새로 만들면 기존 DEK_BIO 봉인은 더 이상 못 연다 — 같은 자리에서 지운다
                // (취소된 등록이 "지문 켜짐" 인데 doFinal 은 영원히 실패하는 상태로 남지 않도록).
                store.edit { it.remove(K.DEK_BIO) }
                val key = generateBioKey()
                Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key) }
            }.getOrNull() ?: return false

        val authorised = authenticate(activity, cipher, "지문 등록") ?: return false
        val sealed = runCatching { authorised.iv + authorised.doFinal(dek) }.getOrNull() ?: return false
        store.edit { it[K.DEK_BIO] = sealed.b64() }
        return true
    }

    suspend fun unlock(activity: FragmentActivity): Boolean =
        runCatching { unlockInner(activity) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrDefault(false)

    private suspend fun unlockInner(activity: FragmentActivity): Boolean {
        val blob = (store.data.first()[K.DEK_BIO] ?: return false).unb64()
        val key = bioKey() ?: return false
        val cipher =
            try {
                Cipher.getInstance(TRANSFORMATION).apply {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(BIO_TAG_BITS, blob, 0, BIO_IV_BYTES))
                }
            } catch (e: KeyPermanentlyInvalidatedException) {
                store.edit { it.remove(K.DEK_BIO) } // 지문이 새로 등록되어 키가 무효화됐다 — PIN 으로 열고 다시 등록해야 한다
                return false
            } catch (e: GeneralSecurityException) {
                return false
            }

        val authorised = authenticate(activity, cipher, "지문으로 잠금 해제") ?: return false
        val dek =
            runCatching { authorised.doFinal(blob, BIO_IV_BYTES, blob.size - BIO_IV_BYTES) }.getOrNull()
                ?: return false
        vault.unlockWith(dek)
        return true
    }

    suspend fun disable() {
        runCatching { store.edit { it.remove(K.DEK_BIO) } }
            .onFailure { if (it is CancellationException) throw it }
    }

    /** 인증 성공이면 인증된 [Cipher], 취소·실패면 null. */
    private suspend fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
    ): Cipher? =
        suspendCancellableCoroutine { continuation ->
            val prompt =
                BiometricPrompt(
                    activity,
                    activity.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (continuation.isActive) continuation.resume(result.cryptoObject?.cipher)
                        }

                        override fun onAuthenticationError(
                            errorCode: Int,
                            errString: CharSequence,
                        ) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    },
                )
            prompt.authenticate(
                BiometricPrompt.PromptInfo
                    .Builder()
                    .setTitle(title)
                    .setNegativeButtonText("PIN 사용")
                    .setAllowedAuthenticators(BIOMETRIC_STRONG)
                    .setConfirmationRequired(false)
                    .build(),
                BiometricPrompt.CryptoObject(cipher),
            )
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        }

    companion object {
        /** false 면 등록 제안도 설정 토글도 아예 보여주지 않는다 (키 생성이 예외를 던지는 기기가 있다). */
        fun available(context: Context): Boolean =
            BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS
    }
}

private fun bioKey(): SecretKey? {
    val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    return keyStore.getKey(BIO_ALIAS, null) as? SecretKey
}

/**
 * 사용할 때마다 Class 3 생체 인증을 요구하는 AES 키. 지문이 새로 등록되면 무효화된다.
 * `setUnlockedDeviceRequired` 는 붙이지 않는다 — per-use 생체 키에는 잉여이고
 * 일부 Android 12 기기에서 "device locked" 오류의 원인이다.
 */
private fun generateBioKey(): SecretKey {
    val spec =
        KeyGenParameterSpec
            .Builder(
                BIO_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(BIO_KEY_BITS)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            .setInvalidatedByBiometricEnrollment(true)
            .build()
    return KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        .apply { init(spec) }
        .generateKey()
}
