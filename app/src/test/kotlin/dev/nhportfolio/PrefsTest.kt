package dev.nhportfolio

import androidx.datastore.preferences.core.emptyPreferences
import dev.nhportfolio.store.cashKey
import dev.nhportfolio.store.readCashCodes
import dev.nhportfolio.store.readTargets
import dev.nhportfolio.store.targetsKey
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
}
