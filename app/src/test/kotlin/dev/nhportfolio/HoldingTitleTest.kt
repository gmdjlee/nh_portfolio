package dev.nhportfolio

import dev.nhportfolio.model.Holding
import dev.nhportfolio.portfolio.holdingTitle
import kotlin.test.Test
import kotlin.test.assertEquals

private fun holding(
    code: String,
    productType: String = "",
) = Holding(
    code = code,
    name = "삼성전자",
    qty = 1,
    remainQty = 1,
    avgPrice = 1,
    price = 1,
    evalAmt = 1,
    pnlRate = 0.0,
    productType = productType,
)

class HoldingTitleTest {
    @Test
    fun `종목코드가 하나뿐이면 종목명만 보여준다`() {
        val h = holding("005930", productType = "위탁")
        assertEquals("삼성전자", holdingTitle(listOf(h), h.key))
    }

    @Test
    fun `같은 종목코드가 현금분 신용분 두 줄이면 상품유형명을 덧붙인다`() {
        val cash = holding("005930", productType = "위탁")
        val credit = holding("005930", productType = "신용융자")

        assertEquals("삼성전자 (위탁)", holdingTitle(listOf(cash, credit), cash.key))
        assertEquals("삼성전자 (신용융자)", holdingTitle(listOf(cash, credit), credit.key))
    }

    @Test
    fun `상품유형명이 비어 있으면 지어내지 않고 종목명만 보여준다`() {
        val blank = holding("005930", productType = "")
        val credit = holding("005930", productType = "신용융자")

        assertEquals("삼성전자", holdingTitle(listOf(blank, credit), blank.key))
    }

    @Test
    fun `key 에 해당하는 보유가 없으면 key 를 그대로 돌려준다`() {
        assertEquals("사라진|위탁", holdingTitle(emptyList(), "사라진|위탁"))
    }
}
