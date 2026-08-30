package dev.nhportfolio.store

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * 계좌별 저장 키. 여러 화면이 같은 계좌의 설정을 읽고 쓰므로 규칙을 한 군데에 둔다 —
 * 화면마다 따로 만들면 한쪽만 바뀌었을 때 설정이 조용히 갈라진다.
 *
 * 계좌번호를 키 이름에 그대로 쓰지 않는다. 저장값 자체는 평문이지만, 계좌번호가 키 이름으로
 * 남으면 값을 읽지 않고 파일 목록만 봐도 계좌번호를 알 수 있다.
 */
private fun accountKey(
    prefix: String,
    acctNo: String,
): Preferences.Key<String> {
    val digest = MessageDigest.getInstance("SHA-256").digest(acctNo.toByteArray())
    return stringPreferencesKey(prefix + digest.joinToString("") { "%02x".format(it) }.take(16))
}

fun targetsKey(acctNo: String): Preferences.Key<String> = accountKey("targets_", acctNo)

fun cashKey(acctNo: String): Preferences.Key<String> = accountKey("cash_", acctNo)

/** 사용자가 붙인 계좌 이름. NH API 는 계좌명을 주지 않는다. */
fun nameKey(acctNo: String): Preferences.Key<String> = accountKey("name_", acctNo)

private const val FULL_BP = 10_000

/** 저장값이 깨졌거나 범위를 벗어나도 화면이 죽지 않는다. */
fun readTargets(
    prefs: Preferences,
    key: Preferences.Key<String>,
): Map<String, Int> =
    runCatching { Json.decodeFromString<Map<String, Int>>(prefs[key] ?: "{}") }
        .getOrDefault(emptyMap())
        .filterValues { it in 0..FULL_BP }

/** 저장값이 깨져도 화면이 죽지 않는다 — 지정이 없는 것으로 본다. */
fun readCashCodes(
    prefs: Preferences,
    key: Preferences.Key<String>,
): Set<String> = runCatching { Json.decodeFromString<Set<String>>(prefs[key] ?: "[]") }.getOrDefault(emptySet())
