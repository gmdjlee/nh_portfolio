package dev.nhportfolio.model

/**
 * NH 계좌. 운영(acct_type 01·02) 계좌만 여기까지 도달한다.
 * [no] 는 acctinfo 의 acct_no 이며 그대로 잔고 API 의 act_no 로 쓴다.
 */
data class Account(
    val no: String,
)

/**
 * 보유 종목 한 줄. 시장 무관 — 금액은 KRW, 수량은 정수.
 *
 * 같은 종목코드가 현금 매수분과 신용/융자 매수분으로 **두 줄** 올 수 있다.
 * 그래서 줄의 신원은 [code] 가 아니라 [key] 다.
 *
 * @param code 종목코드 (iem_cd)
 * @param name 종목명 (iem_nm)
 * @param qty 보유수량 (itg_bnc_qty)
 * @param remainQty 잔고수량 (rsdl_qty)
 * @param avgPrice 평균매입가 (phs_pr)
 * @param price 현재가 (now_pr)
 * @param evalAmt 평가금액 (eal_amt)
 * @param pnlRate 수익률 (pft_rt)
 * @param productType 상품유형명 (pdt_tp_nm) — 배지에 그대로 쓴다
 * @param loanAmt 대출잔고금액 (lon_bnc_amt)
 * @param loanDate 대출매수일자 (lon_byn_dt)
 */
data class Holding(
    val code: String,
    val name: String,
    val qty: Long,
    val remainQty: Long,
    val avgPrice: Long,
    val price: Long,
    val evalAmt: Long,
    val pnlRate: Double,
    val productType: String = "",
    val loanAmt: Long = 0,
    val loanDate: String = "",
) {
    /**
     * 줄의 신원. 목표 비중·현금성 지정·선택이 전부 이 값으로 키가 잡힌다.
     *
     * 상품유형명을 붙이는 이유는 NH 자신이 그 이름으로 현금분과 신용분을 가르기 때문이다.
     * 대출매수일자까지 넣으면 대출 건별로 더 갈리지만, 날짜가 바뀌면 저장된 목표가
     * 고아가 된다 — 얻는 것보다 잃는 것이 크다.
     */
    val key: String get() = "$code|$productType"

    /**
     * 신용/융자로 산 줄인가. 상품유형명 문자열을 비교하지 않는다 — 현금분의 이름이
     * 무엇인지 모르는 채로 하드코딩하면 배지가 전부 붙거나 하나도 안 붙는다.
     */
    val onCredit: Boolean get() = loanAmt > 0 || loanDate.isNotBlank()
}

/** [cash] 는 D+2 예수금 — 당일 체결이 즉시 반영된다. */
data class Balance(
    val cash: Long,
    val holdings: List<Holding>,
)

/** 실시간 체결통보 한 건. [time] 은 HHmmss. */
data class Fill(
    val acctNo: String,
    val name: String,
    val qty: Long,
    val price: Long,
    val time: String,
)
