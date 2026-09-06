package dev.nhportfolio.store

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.nhportfolio.market.Applied
import kotlinx.serialization.Serializable
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

fun targetsKey(acctNo: String): Preferences.Key<String> = accountKey("targets3_", acctNo)

fun cashKey(acctNo: String): Preferences.Key<String> = accountKey("cash3_", acctNo)

/** 사용자가 붙인 계좌 이름. NH API 는 계좌명을 주지 않는다. */
fun nameKey(acctNo: String): Preferences.Key<String> = accountKey("name_", acctNo)

/** "이 목표로 맞추기" 를 누른 기록(사양 §4.2). 계좌마다 판정·적용 여부가 갈리므로 계좌별 키다. */
fun appliedKey(acctNo: String): Preferences.Key<String> = accountKey("applied_", acctNo)

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

/** DataStore 에 저장하는 적용 기록 행의 형태. [Applied] 는 `market/` 의 순수 타입이라
 *  kotlinx.serialization 을 모른다 — 저장·복원은 이 DTO 를 거쳐서만 한다. */
@Serializable
private data class AppliedDto(
    val date: String,
    val exposureBp: Int,
)

private val APPLIED_DATE = Regex("^\\d{8}$")

/** 저장값이 깨졌거나 행이 범위를 벗어나도 화면이 죽지 않는다 — 날짜가 8자리 숫자가 아니거나
 *  exposureBp 가 0~10000 을 벗어난 행만 버리고 나머지는 살린다. */
fun readApplied(
    prefs: Preferences,
    key: Preferences.Key<String>,
): List<Applied> =
    runCatching { Json.decodeFromString<List<AppliedDto>>(prefs[key] ?: "[]") }
        .getOrDefault(emptyList())
        .filter { APPLIED_DATE.matches(it.date) && it.exposureBp in 0..FULL_BP }
        .map { Applied(it.date, it.exposureBp) }

/** DataStore 한 번의 `edit` 이 파일 전체를 다시 쓰므로, 무한히 쌓이면 "이 목표로 맞추기"
 *  한 번의 비용이 계속 커진다 — 최근 [MAX_APPLIED] 건만 남긴다. 연 몇 회 뿐이라 1000건이면
 *  수백 년 분이다. */
private const val MAX_APPLIED = 1_000

/** [current] 뒤에 [date]·[exposureBp] 한 줄을 붙인 새 목록. 순수 함수라 호출부(뷰모델)가
 *  같은 `store.edit` 안에서 목표 재기록과 함께 검증 없이 바로 쓸 수 있다. */
fun withApplied(
    current: List<Applied>,
    date: String,
    exposureBp: Int,
): List<Applied> = (current + Applied(date, exposureBp)).takeLast(MAX_APPLIED)

/** [withApplied] 가 만든 목록을 저장 형식으로 바꾼다. [Applied] 자체는 직렬화 대상이 아니라 [AppliedDto] 를 거친다. */
fun encodeApplied(list: List<Applied>): String = Json.encodeToString(list.map { AppliedDto(it.date, it.exposureBp) })

/**
 * 종목코드로 저장하던 옛 목표·현금성 지정을 지운다.
 *
 * 신원이 `종목코드` → `종목코드|상품유형명` → `종목코드|상품유형명|유형코드명|대출여부` 로
 * 두 차례 바뀌어서 옛 값은 어느 줄 것인지 알 수 없다. 접두사만 올리고 두면 DataStore 에
 * 영원히 남으므로 두 세대 모두 실제로 지운다.
 */
fun clearLegacyKeys(
    prefs: MutablePreferences,
    acctNo: String,
) {
    prefs.remove(accountKey("targets_", acctNo))
    prefs.remove(accountKey("cash_", acctNo))
    prefs.remove(accountKey("targets2_", acctNo))
    prefs.remove(accountKey("cash2_", acctNo))
}

/** 화면 테마. 계좌와 무관한 앱 전체 설정이라 계좌번호 해시를 붙이지 않는다. */
val themeKey: Preferences.Key<String> = stringPreferencesKey("theme")
