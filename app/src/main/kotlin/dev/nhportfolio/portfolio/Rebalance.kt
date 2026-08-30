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

    /**
     * 예수금 목표를 [cashBp] 로 잡고, 종목 목표들의 합이 정확히 `100% - cashBp` 가 되도록
     * 비례 조정한다. 종목끼리의 상대 비율은 그대로 유지된다.
     *
     * 항상 "지금의 종목 합"에서 "남은 자리"로 스케일하므로 되풀이해 불러도 누적 오차가 없다 —
     * 예수금을 10% -> 20% -> 5% 로 바꿔도 매번 옳은 값이 나온다. 원본을 따로 보관할 필요가 없다.
     *
     * 목표가 없는 종목은 [currentWeightsBp] 의 **현재 비중**을 출발점으로 삼는다. 그래야
     * "예수금 X% 를 만들려면 각 종목을 어떻게 조정해야 하는가" 에 모든 종목이 답을 갖는다 —
     * 목표를 하나도 안 잡은 채 예수금만 정해도 전 종목의 조정안이 나온다.
     * 명시한 목표가 있으면 그쪽이 이긴다.
     *
     * 조정할 대상이 전혀 없으면(보유도 목표도 없음) 예수금만 설정한다.
     */
    fun scaleForCash(
        targetsBp: Map<String, Int>,
        cashBp: Int,
        currentWeightsBp: Map<String, Int> = emptyMap(),
    ): Map<String, Int> {
        require(cashBp in 0..FULL_BP) { "목표 비중은 0~100% 범위여야 합니다" }
        val stocks =
            (currentWeightsBp.filterKeys { it != CASH } + targetsBp.filterKeys { it != CASH })
        val sum = stocks.values.sum()
        if (sum <= 0) return targetsBp + (CASH to cashBp)

        val room = (FULL_BP - cashBp).toLong()
        // 단순 비례하면 정수 절삭 때문에 합이 room 에서 어긋난다. 바닥값을 깔고 남은 몫을
        // 나머지가 큰 순서로 1bp 씩 나눠 준다(최대잉여법) — 합이 항상 정확히 room 이 된다.
        val scaled = stocks.mapValues { (_, bp) -> (bp * room / sum).toInt() }.toMutableMap()
        val shortfall = (room - scaled.values.sumOf { it.toLong() }).toInt()
        stocks.entries
            .sortedByDescending { (_, bp) -> bp * room % sum }
            .take(shortfall)
            .forEach { scaled[it.key] = scaled.getValue(it.key) + 1 }
        return scaled + (CASH to cashBp)
    }

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
