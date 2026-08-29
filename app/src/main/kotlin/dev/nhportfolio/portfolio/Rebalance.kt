package dev.nhportfolio.portfolio

import dev.nhportfolio.model.Balance

/**
 * 목표 비중 -> 매수/매도 주식 수. 순수 함수 — 네트워크도 상태도 없다.
 *
 * 비중 단위는 basis point (1250 = 12.50%). 분모는 `예수금 + Σ 평가금액`.
 */
object Rebalance {
    /** 현금 행의 코드. 종목코드가 될 수 없는 값이어야 한다 (NASDAQ 에 "CASH" 티커가 실재한다). */
    const val CASH = "\$CASH"

    private const val FULL_BP = 10_000

    /** [deltaShares] 가 null 이면 목표가 없거나 현재가가 0 이하라 계산할 수 없다는 뜻이다. */
    data class Line(
        val code: String,
        val currentAmt: Long,
        val weightBp: Int,
        val targetBp: Int?,
        val deltaShares: Long?,
    )

    /** [lines] 의 마지막 원소는 항상 현금 행이다. */
    data class Plan(
        val lines: List<Line>,
        val total: Long,
        val cashAfter: Long,
        val targetSumBp: Int,
    )

    fun plan(
        balance: Balance,
        targetsBp: Map<String, Int>,
    ): Plan {
        require(targetsBp.values.all { it in 0..FULL_BP }) { "목표 비중은 0~100% 범위여야 합니다" }

        val total = balance.cash + balance.holdings.sumOf { it.evalAmt }
        var spend = 0L
        val holdingLines =
            balance.holdings.map { h ->
                val targetBp = targetsBp[h.code]
                val delta =
                    if (targetBp == null || h.price <= 0) {
                        null
                    } else {
                        total * targetBp / FULL_BP / h.price - h.qty
                    }
                if (delta != null) spend += delta * h.price
                Line(h.code, h.evalAmt, weightBp(h.evalAmt, total), targetBp, delta)
            }
        val lines = holdingLines + Line(CASH, balance.cash, weightBp(balance.cash, total), targetsBp[CASH], null)
        return Plan(
            lines = lines,
            total = total,
            cashAfter = balance.cash - spend,
            targetSumBp = lines.sumOf { it.targetBp ?: 0 },
        )
    }

    private fun weightBp(
        amount: Long,
        total: Long,
    ): Int = if (total <= 0) 0 else (amount * FULL_BP / total).toInt()
}
