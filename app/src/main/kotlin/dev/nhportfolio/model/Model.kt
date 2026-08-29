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
 * @param code 종목코드 (iem_cd)
 * @param name 종목명 (iem_nm)
 * @param qty 보유수량 (itg_bnc_qty)
 * @param remainQty 잔고수량 (rsdl_qty)
 * @param avgPrice 평균매입가 (phs_pr)
 * @param price 현재가 (now_pr)
 * @param evalAmt 평가금액 (eal_amt)
 * @param pnlRate 수익률 (pft_rt)
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
)

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
