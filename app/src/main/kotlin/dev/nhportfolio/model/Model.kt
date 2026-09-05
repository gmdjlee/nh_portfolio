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
 * @param typeName 유형코드명 (tp_cd_nm)
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
    val typeName: String = "",
) {
    /**
     * 줄의 신원. 목표 비중·현금성 지정·선택이 전부 이 값으로 키가 잡힌다.
     *
     * **유형코드명(`tp_cd_nm`)이 실제로 줄을 가르는 값이다.** 실기기 확인 결과(2026-09-06)
     * 이 값은 일반 1건 + 신용 2건짜리 종목, 일반 1건 + 신용 1건짜리 종목 모두에서 행마다
     * 전부 달랐다 — 신용 두 건처럼 대출 여부만으로는 못 가르는 행도 갈라 준다. 통합잔고
     * 유형코드(`itg_bnc_tp_cd`)는 같은 확인에서 모든 행이 빈 값으로 왔다 — 신원은 물론
     * 화면 표시에도 못 써 필드 자체를 없앴다.
     *
     * 상품유형명(`pdt_tp_nm`)은 그대로 둔다. 실기기에서는 전 행이 빈 값이라 지금은 기여하지
     * 않지만, 채워 오는 계좌·상품이 있다면 공짜로 한 겹 더 가른다.
     *
     * 대출 여부(`onCredit`)도 그대로 둔다. 유형코드명이 주 구분자이지만, 그 값이 비어 오는
     * 계좌가 있을 수 있다 — 그런 계좌에서도 최소한 현금분과 신용분은 갈리도록 두 겹으로 막는다.
     *
     * 대출매수일자는 넣지 않는다. 대출 건별로 더 갈리지만 날짜가 바뀌면 저장된 목표가
     * 고아가 된다 — 얻는 것보다 잃는 것이 크다. 같은 코드에서 상품유형명·유형코드명·대출
     * 여부가 전부 같은 두 건이 오면 그래도 갈리지 않고, 그 경우는 [Balance] 를 받는 쪽에서
     * 중복 신원 경고로 알린다.
     */
    val key: String get() = "$code|$productType|$typeName|${if (onCredit) "L" else ""}"

    /**
     * 신용/융자로 산 줄인가. 상품유형명 문자열을 비교하지 않는다 — 현금분의 이름이
     * 무엇인지 모르는 채로 하드코딩하면 배지가 전부 붙거나 하나도 안 붙는다.
     *
     * 날짜는 `isNotBlank()` 가 아니라 "숫자 1~9 가 있는가"로 본다 — 일부 증권사 API 는
     * 날짜가 없을 때 빈 문자열 대신 "00000000" 을 채워 보낸다. `isNotBlank()` 면 그 값도
     * true 로 잡혀 전 종목에 배지가 붙는다. NH PLUG 는 고정폭 필드라 공백 채움("        ")도
     * 올 수 있어 `!= '0'` 대신 `in '1'..'9'` 로 잡는다 — `!= '0'` 이면 공백도 걸려 마찬가지로
     * 전 종목에 배지가 붙는다.
     */
    val onCredit: Boolean get() = loanAmt > 0 || loanDate.any { it in '1'..'9' }
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
