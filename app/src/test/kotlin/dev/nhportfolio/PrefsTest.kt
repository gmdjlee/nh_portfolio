package dev.nhportfolio

import androidx.datastore.preferences.core.emptyPreferences
import dev.nhportfolio.market.Applied
import dev.nhportfolio.store.appliedKey
import dev.nhportfolio.store.cashKey
import dev.nhportfolio.store.clearLegacyKeys
import dev.nhportfolio.store.readApplied
import dev.nhportfolio.store.readCashCodes
import dev.nhportfolio.store.readTargets
import dev.nhportfolio.store.targetsKey
import dev.nhportfolio.store.themeKey
import dev.nhportfolio.store.withApplied
import dev.nhportfolio.ui.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class PrefsTest {
    @Test
    fun `targetsKey 와 cashKey 는 이름이 다르고 계좌번호를 담지 않는다`() {
        val targets = targetsKey("111").name
        val cash = cashKey("111").name

        assertNotEquals(targets, cash)
        assertFalse("111" in targets, "계좌번호가 키 이름에 그대로 남았다: $targets")
        assertFalse("111" in cash, "계좌번호가 키 이름에 그대로 남았다: $cash")
    }

    @Test
    fun `targetsKey 는 계좌마다 다르다`() {
        assertNotEquals(targetsKey("111").name, targetsKey("222").name)
    }

    @Test
    fun `readTargets 는 깨진 JSON 이면 빈 맵을 준다`() {
        val key = targetsKey("111")
        val prefs = emptyPreferences().toMutablePreferences()
        prefs[key] = "이건 JSON 이 아니다"

        assertEquals(emptyMap(), readTargets(prefs, key))
    }

    @Test
    fun `readTargets 는 범위 밖 값을 걸러낸다`() {
        val key = targetsKey("111")
        val prefs = emptyPreferences().toMutablePreferences()
        prefs[key] = """{"005930":-1,"000660":10001,"035420":5000}"""

        assertEquals(mapOf("035420" to 5_000), readTargets(prefs, key))
    }

    @Test
    fun `readCashCodes 는 깨진 JSON 이면 빈 집합을 준다`() {
        val key = cashKey("111")
        val prefs = emptyPreferences().toMutablePreferences()
        prefs[key] = "이건 JSON 이 아니다"

        assertEquals(emptySet(), readCashCodes(prefs, key))
    }

    @Test
    fun `모르는 테마 저장값은 자동으로 떨어진다`() {
        val prefs = emptyPreferences().toMutablePreferences()
        assertEquals(ThemeMode.AUTO, ThemeMode.from(prefs[themeKey]), "저장이 없으면 시스템을 따라야 한다")

        prefs[themeKey] = "SEPIA" // 손으로 고쳤거나 나중 버전이 쓰던 값
        assertEquals(ThemeMode.AUTO, ThemeMode.from(prefs[themeKey]))

        // 이름으로 저장하므로 상수 이름이 바뀌면 저장된 설정이 조용히 초기화된다.
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.from(it.name)) }
    }

    @Test
    fun `appliedKey 는 계좌마다 다르고 계좌번호를 키 이름에 담지 않는다`() {
        val a = appliedKey("111").name
        val b = appliedKey("222").name

        assertNotEquals(a, b)
        assertFalse("111" in a, "계좌번호가 키 이름에 그대로 남았다: $a")
        assertFalse("222" in b, "계좌번호가 키 이름에 그대로 남았다: $b")
    }

    @Test
    fun `readApplied 는 깨진 JSON 이면 빈 목록을 준다`() {
        val key = appliedKey("111")
        val prefs = emptyPreferences().toMutablePreferences()
        prefs[key] = "이건 JSON 이 아니다"

        assertEquals(emptyList(), readApplied(prefs, key))
    }

    @Test
    fun `readApplied 는 날짜가 8자리가 아니거나 exposureBp 가 범위 밖인 행을 버리고 나머지는 남긴다`() {
        val key = appliedKey("111")
        val prefs = emptyPreferences().toMutablePreferences()
        prefs[key] =
            """[{"date":"2026090","exposureBp":2500},{"date":"20260906","exposureBp":-1},""" +
            """{"date":"20260906","exposureBp":10001},{"date":"20260907","exposureBp":5000}]"""

        assertEquals(listOf(Applied("20260907", 5_000)), readApplied(prefs, key))
    }

    @Test
    fun `withApplied 는 기존 목록 뒤에 순서대로 덧붙인다`() {
        val step1 = withApplied(emptyList(), "20260101", 1_000)

        val step2 = withApplied(step1, "20260102", 2_000)

        assertEquals(listOf(Applied("20260101", 1_000), Applied("20260102", 2_000)), step2)
    }

    @Test
    fun `withApplied 는 최근 1000건만 남기고 오래된 항목을 버린다`() {
        var list = emptyList<Applied>()
        repeat(1_001) { i -> list = withApplied(list, "20260101", i) }

        assertEquals(1_000, list.size)
        assertEquals(1, list.first().exposureBp, "가장 오래된 0번째 항목은 잘려나가야 한다")
        assertEquals(1_000, list.last().exposureBp)
    }

    @Test
    fun `clearLegacyKeys 는 살아있는 키를 건드리지 않는다`() {
        // accountKey 가 private 이라 지워지는 legacy 키(targets_·cash_·targets2_·cash2_)
        // 자체는 여기서 못 만든다 — 대신 살아있는 키(targetsKey·cashKey, 지금은 targets3_·
        // cash3_)가 절대 건드려지지 않는다는 것으로 안전을 확인한다. 접두사가 뒤바뀌면
        // 앱을 열 때마다 방금 잡은 목표가 통째로 사라진다.
        val acctNo = "111"
        val prefs = emptyPreferences().toMutablePreferences()
        prefs[targetsKey(acctNo)] = """{"005930":5000}"""
        prefs[cashKey(acctNo)] = """["005930"]"""

        clearLegacyKeys(prefs, acctNo)

        assertEquals("""{"005930":5000}""", prefs[targetsKey(acctNo)])
        assertEquals("""["005930"]""", prefs[cashKey(acctNo)])
    }
}
