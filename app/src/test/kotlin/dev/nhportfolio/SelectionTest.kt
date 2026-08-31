package dev.nhportfolio

import dev.nhportfolio.portfolio.selectionOf
import dev.nhportfolio.portfolio.toggleAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SelectionTest {
    @Test
    fun `일부만 선택했으면 전체 선택으로 채운다`() {
        val selectable = listOf("A", "B", "C")
        val selection = selectionOf(setOf("A"), selectable)

        assertEquals(setOf("A", "B", "C"), toggleAll(selection, selectable))
    }

    @Test
    fun `전부 선택했으면 전체 선택을 누르면 비운다`() {
        val selectable = listOf("A", "B", "C")
        val selection = selectionOf(setOf("A", "B", "C"), selectable)

        assertTrue(selection.allSelected)
        assertEquals(emptySet(), toggleAll(selection, selectable))
    }

    @Test
    fun `종목코드가 중복돼도 전체 선택 상태가 정확하고 해제도 된다`() {
        // NH 잔고 응답이 같은 종목코드를 두 번 내려주면 selectable 은 5행 4종목이 될 수 있다(F1).
        // 개수(5)가 아니라 코드 집합(4)으로 비교해야 전부 선택했을 때 allSelected 가 true 다 —
        // size-vs-size 로 구현하면 4 != 5 라 여기서 실패한다.
        val selectable = listOf("A", "B", "C", "D", "D")
        val selection = selectionOf(setOf("A", "B", "C", "D"), selectable)

        assertTrue(selection.allSelected, "중복 코드 때문에 전체 선택이 인식되지 않았다: $selection")
        assertEquals(emptySet(), toggleAll(selection, selectable))
    }

    @Test
    fun `잔고에서 사라진 유령 코드는 선택에서 빠지고 전체 선택을 막지 않는다`() {
        val selectable = listOf("A", "B")
        val selection = selectionOf(setOf("A", "B", "사라진코드"), selectable)

        assertEquals(setOf("A", "B"), selection.codes)
        assertTrue(selection.allSelected)
    }

    @Test
    fun `고를 종목이 없으면 hasSelectable 은 false 다`() {
        val selection = selectionOf(emptySet(), emptyList())

        assertFalse(selection.hasSelectable)
        assertFalse(selection.allSelected)
    }
}
