# 신용/융자 보유 구분 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 같은 종목의 현금 매수분과 신용/융자 매수분을 별개의 줄로 다루고, 신용/융자 줄에 NH 가 준 상품유형명을 배지로 표시한다.

**Architecture:** 지금 앱은 **종목코드를 줄의 신원으로** 쓴다 — 목표 비중 맵, 현금성 지정, 선택 집합, 보유 명세 조회가 전부 `code` 로 키가 잡힌다. NH 잔고가 같은 종목코드를 현금분·신용분 두 줄로 주면 이 신원이 무너진다. 이 계획의 핵심은 **`Holding.key`(종목코드 + 상품유형명)를 도입하고 `code` 를 쓰던 모든 자리를 `key` 로 옮기는 것**이다. 표시(배지)는 그 뒤에 붙는 얇은 층이다.

**Tech Stack:** Kotlin 2.4.10 · Compose BOM 2026.08.00 · Ktor 3.5.2 · kotlinx.serialization · DataStore · detekt 1.23.8 · ktlint-gradle 14.2.0 · JDK 21

**Spec:** 사용자 요청 원문(아래) + 사용자가 고른 3개 답 + `https://www.nhplug.com/openapi-docs/krstock/openapi.json` (2026-08-31 재확인)

**Base:** 브랜치 `feat/tab-merge` (`90702f8`) 위에서 시작한다. 그 브랜치는 리뷰까지 끝났으나 아직 `main` 에 병합되지 않았다.

---

## 요구사항 원문

> 신용/융자로 매입한 종목을 구분하기 위해 해당 종목에 종목 표시에 신용/융자 표시할 수 있는 기능을 넣고, 현재 동일 종목에 대해 현금 매수와 신용/융자 매수가 같이 존재할 때 둘 중 하나를 선택하면 같이 선택되는 것을 별도로 선택되도록 만들어 주세요.

## 사용자가 고른 답 (구현자는 재논의하지 말 것)

| 질문 | 답 |
|---|---|
| 현금분과 신용분의 목표 비중 | **완전히 분리** — 두 줄이 각각 독립된 목표를 갖는다 |
| 이미 저장된 목표 비중 | **전부 지우고 다시 잡기** — 이관하지 않는다 |
| 배지 문구 | **NH 가 준 상품유형명(`pdt_tp_nm`) 그대로** |

## Global Constraints

- **거래 기능은 절대 구현하지 않는다.** 매수/매도 수량 표시까지가 전부다.
- **새 의존성 금지.**
- 로그·문서·오류 메시지·커밋에 키·토큰·계좌번호·PII 금지.
- **사용자에게 보이는 모든 문자열, 주석, 커밋 메시지는 한국어.**
- Kotlin only. detekt `MaxLineLength: 140`, `CyclomaticComplexMethod` 임계 15 — **복잡도는 추출로 푼다, `@Suppress` 금지.**
- ktlint 는 import 순서와 `chain-method-continuation`(긴 `?.`/`.` 체인은 줄바꿈)을 강제한다. 필요하면 `./gradlew ktlintFormat` 후 `git diff` 로 포매팅만 바뀌었는지 확인한다.
- **검증 게이트:** `./gradlew assembleDebug detekt ktlintCheck testDebugUnitTest`. 현재 **114개** 테스트가 통과 중이며, 이 계획은 테스트를 **늘린다**.
- `git push` 는 명시적 지시가 있을 때만.
- `model/**` 와 `portfolio/Rebalance.kt` 는 detekt `ForbiddenImport` 로 `android.*`·`androidx.*`·`io.ktor.*`·`org.koin.*`·`kotlinx.serialization.*` import 가 금지된 순수 도메인이다.

---

## 사전 검증 (이미 확인함 — 다시 조사하지 말 것)

`https://www.nhplug.com/openapi-docs/krstock/openapi.json` 의 `/krstock/inquiry/v1/balance` Output_1 실제 필드 (2026-08-31 조회):

| 필드 | 한글 설명 | 이 계획에서 |
|---|---|---|
| `pdt_tp_nm` | 상품유형명 | **배지 문구 + 신원의 두 번째 조각** |
| `lon_bnc_amt` | 대출잔고금액 (integer) | **신용/융자 판정** |
| `lon_byn_dt` | 대출매수일자 (string) | **신용/융자 판정 보조** |
| `ctc_int_rt` | 약정이자율 | 쓰지 않는다 |
| `wtm_rt` | 증거금율 | 쓰지 않는다 |
| `itg_bnc_tp_cd` | 통합잔고유형코드 | 쓰지 않는다 (이름이 더 읽힌다) |
| `tp_cd_nm` | 유형코드명 | 쓰지 않는다 |

**주의:** 잔고 Output_1 에 `cfd_pdt_tp_nm` 라는 필드는 **없다.** 실제 이름은 `pdt_tp_nm` 이다. (계획 작성 당시 이 문단은 프로젝트 메모리에 잘못된 항목이 있다고 적었으나, 확인 결과 메모리에는 그런 기록이 없었다 — 착오였다.) 이 표를 쓴다.

**아직 모르는 것:** 실제 응답에서 `lon_bnc_amt`/`lon_byn_dt` 가 채워져 오는지, 현금분의 `pdt_tp_nm` 이 어떤 글자인지는 **실계좌 응답을 봐야 안다.** 이 계획은 그 값을 넘겨짚지 않도록 설계했다 — 판정은 문자열 비교가 아니라 대출잔고 유무로 하고, 배지 문구는 NH 가 준 글자를 그대로 낸다. 기기 확인 후 어긋나면 한 줄 고치면 된다.

---

## 확정된 설계 결정

**D1. 줄의 신원은 `종목코드|상품유형명` 이다.**
가장 안정적이면서 NH 자신이 구분하는 만큼만 구분한다. 대출매수일자를 넣으면 같은 종목의 여러 대출 건까지 갈리지만, 날짜가 바뀌면 저장된 목표가 고아가 된다 — 이득보다 취약함이 크다. NH 가 같은 `(종목코드, 상품유형명)` 을 두 줄로 준다면 NH 스스로도 둘을 구분하지 않는 것이므로 배지로도 가를 수 없다. 그 경우는 D2 가 받는다.

**D2. 신원이 겹치면 조용히 두 배로 세지 말고 화면에 알린다.**
`(종목코드, 상품유형명)` 이 중복되면 목표 비중이 두 줄에 같이 걸려 매수 수량이 두 배가 된다 — 이 앱에서 가장 나쁜 결함이다. 감지해서 경고 한 줄을 띄운다. 고칠 수는 없어도 **틀린 숫자를 말없이 보여주지는 않는다.**

**D3. 신용/융자 판정은 `대출잔고금액 > 0 || 대출매수일자 비어있지 않음` 이다.**
상품유형명 문자열을 하드코딩해 비교하지 않는다 — 실제 값이 "위탁" 인지 "현금" 인지 "주식" 인지 모르고, 틀리면 모든 줄에 배지가 붙거나 하나도 안 붙는다. 대출잔고는 의미로 판정하므로 문자열을 몰라도 된다.

**D4. 배지 문구는 `pdt_tp_nm` 을 그대로, 비어 있으면 "신용".**
이 프로젝트는 오류 메시지도 NH 문구를 그대로 쓴다. 우리가 지어낸 말보다 실제와 어긋날 위험이 없다.

**D5. 저장된 목표 비중과 현금성 지정을 **지운다** — 사용자 선택.**
DataStore 키 접두사를 `targets_`→`targets2_`, `cash_`→`cash2_` 로 올리고, 읽을 때 옛 키가 남아 있으면 **삭제한다.** 접두사만 올리고 옛 값을 두면 DataStore 에 영원히 남는다.

**D6. `Line.code` 를 `Line.key` 로 이름까지 바꾼다.**
의미가 바뀌었는데 이름을 두면 다음 사람이 종목코드로 착각한다. `RebalanceTest` 45개가 함께 바뀐다 — 기계적 변경이다.

**D7. 고아 목표(보유에서 사라진 키에 남은 목표)는 이 계획에서 건드리지 않는다.**
선행 문제이고 직전 최종 리뷰가 범위 밖으로 미뤘다. D5 의 초기화로 시작 시점에는 고아가 0이다. 남는 위험에 적는다.

---

## 파일 구조

| 파일 | 이 계획에서 |
|---|---|
| `app/src/main/kotlin/dev/nhportfolio/model/Model.kt` | **수정** — `Holding` 에 상품유형·대출 정보·`key` 추가 |
| `app/src/main/kotlin/dev/nhportfolio/api/NhApi.kt` | **수정** — `HoldingDto` 가 새 필드 3개를 읽는다 |
| `app/src/main/kotlin/dev/nhportfolio/portfolio/Rebalance.kt` | **수정** — `Line.code`→`key`, 계산이 key 로 돈다, 중복 감지 |
| `app/src/main/kotlin/dev/nhportfolio/store/Prefs.kt` | **수정** — 키 접두사 올림 + 옛 키 삭제 |
| `app/src/main/kotlin/dev/nhportfolio/portfolio/PortfolioScreen.kt` | **수정** — 배지 표시, 선택·조회를 key 로 |
| `app/src/test/kotlin/dev/nhportfolio/RebalanceTest.kt` | **수정** — `Line.key` 반영 + 중복 종목 테스트 추가 |
| `app/src/test/kotlin/dev/nhportfolio/NhApiTest.kt` | **수정** — 새 필드 파싱 테스트 추가 |
| `app/src/test/kotlin/dev/nhportfolio/SelectionTest.kt` | **수정** — 신원이 key 임을 반영 |
| `design/Main.dc.html`, `design/Selecting.dc.html` | **수정** — 배지와 분리된 두 줄 |

---

## Task 1: 도메인 — 줄의 신원을 종목코드에서 `key` 로 옮긴다

이 계획에서 **숫자가 틀릴 수 있는 유일한 태스크**다. 나머지는 표시층이다.

**Files:**
- Modify: `app/src/main/kotlin/dev/nhportfolio/model/Model.kt`
- Modify: `app/src/main/kotlin/dev/nhportfolio/api/NhApi.kt` (`HoldingDto`, 437-448행)
- Modify: `app/src/main/kotlin/dev/nhportfolio/portfolio/Rebalance.kt`
- Modify: `app/src/test/kotlin/dev/nhportfolio/RebalanceTest.kt`
- Modify: `app/src/test/kotlin/dev/nhportfolio/NhApiTest.kt`

**Interfaces (Produces):**
- `Holding(code, name, qty, remainQty, avgPrice, price, evalAmt, pnlRate, productType: String = "", loanAmt: Long = 0, loanDate: String = "")`
- `Holding.key: String` — `"$code|$productType"`
- `Holding.onCredit: Boolean` — `loanAmt > 0 || loanDate.isNotBlank()`
- `Rebalance.Line(key, currentAmt, weightBp, targetBp, deltaShares)` — 첫 인자 이름이 `code` 에서 `key` 로 바뀐다
- `Rebalance.Plan(..., val duplicateKeys: Boolean = false)`

- [ ] **Step 1: 실패하는 테스트를 먼저 쓴다 — 중복 종목이 목표를 나눠 갖는가**

`app/src/test/kotlin/dev/nhportfolio/RebalanceTest.kt` 맨 아래에 붙인다. 파일 상단의 `holding(...)` 헬퍼는 새 필드에 기본값이 있으므로 그대로 쓴다.

```kotlin
    /** 같은 종목코드가 현금분·신용분으로 두 줄 오면 목표가 각각 걸려야 한다. */
    @Test
    fun `같은 종목코드도 상품유형이 다르면 목표가 따로 걸린다`() {
        val cash = holding("005930", qty = 100, price = 70_000).copy(productType = "위탁")
        val credit = holding("005930", qty = 50, price = 70_000).copy(productType = "신용융자", loanAmt = 1_000_000)
        val balance = Balance(cash = 0, holdings = listOf(cash, credit))

        val plan = Rebalance.plan(balance, mapOf(credit.key to 5_000))

        val lines = plan.lines.associateBy { it.key }
        assertNull(lines.getValue(cash.key).targetBp, "현금분에는 목표가 없어야 한다")
        assertEquals(5_000, lines.getValue(credit.key).targetBp)
        // 목표 합계가 두 번 세어지면 10_000 이 된다 — 그게 이 테스트가 막는 결함이다.
        assertEquals(5_000, plan.targetSumBp)
    }

    /** 신원이 겹치면 조용히 두 배로 세지 말고 알려야 한다. */
    @Test
    fun `신원이 겹치는 줄이 있으면 표시한다`() {
        val a = holding("005930", qty = 100, price = 70_000).copy(productType = "위탁")
        val b = holding("005930", qty = 50, price = 70_000).copy(productType = "위탁")
        val plan = Rebalance.plan(Balance(cash = 0, holdings = listOf(a, b)), emptyMap())
        assertTrue(plan.duplicateKeys)
    }

    @Test
    fun `상품유형이 다르면 신원이 겹치지 않는다`() {
        val a = holding("005930", qty = 100, price = 70_000).copy(productType = "위탁")
        val b = holding("005930", qty = 50, price = 70_000).copy(productType = "신용융자")
        val plan = Rebalance.plan(Balance(cash = 0, holdings = listOf(a, b)), emptyMap())
        assertFalse(plan.duplicateKeys)
    }
```

- [ ] **Step 2: 테스트가 실패하는 것을 확인한다**

```
./gradlew testDebugUnitTest --tests "dev.nhportfolio.RebalanceTest"
```
기대: 컴파일 실패 (`productType`, `key`, `duplicateKeys` 없음). 그것이 이 단계의 성공이다.

- [ ] **Step 3: `Holding` 에 신용 정보와 신원을 추가한다**

`app/src/main/kotlin/dev/nhportfolio/model/Model.kt` 의 `Holding` 을 통째로 이렇게 바꾼다:

```kotlin
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
```

- [ ] **Step 4: `Rebalance` 를 key 로 돌린다**

`app/src/main/kotlin/dev/nhportfolio/portfolio/Rebalance.kt` 에서 네 곳을 바꾼다.

`Line` 의 첫 프로퍼티 이름:

```kotlin
    /** [deltaShares] 가 null 이면 목표가 없거나 현재가가 0 이하라 계산할 수 없다는 뜻이다. */
    data class Line(
        val key: String,
        val currentAmt: Long,
        val weightBp: Int,
        val targetBp: Int?,
        val deltaShares: Long?,
    )
```

`Plan` 에 중복 표시를 더한다 (기존 프로퍼티는 그대로, 맨 뒤에 추가):

```kotlin
    data class Plan(
        val lines: List<Line>,
        val total: Long,
        val cashAfter: Long,
        val targetSumBp: Int,
        val totalPl: Long = 0,
        val totalPlRate: Double = 0.0,
        /** 신원이 겹치는 줄이 있으면 true — 목표가 두 줄에 같이 걸려 수량이 두 배가 된다. */
        val duplicateKeys: Boolean = false,
    )
```

`foldCash` 의 판정을 key 로 (`cashCodes` 파라미터 이름도 `cashKeys` 로 바꾼다):

```kotlin
    fun foldCash(
        balance: Balance,
        cashKeys: Set<String>,
    ): Balance {
        if (cashKeys.isEmpty()) return balance
        val (cashLike, rest) = balance.holdings.partition { it.key in cashKeys }
        if (cashLike.isEmpty()) return balance
        return Balance(cash = balance.cash + cashLike.sumOf { it.evalAmt }, holdings = rest)
    }
```

`plan` 의 본문 — `h.code` 를 `h.key` 로 바꾸고 중복을 센다:

```kotlin
        val total = balance.cash + balance.holdings.sumOf { it.evalAmt }
        var spend = 0L
        val holdingLines =
            balance.holdings.map { h ->
                val targetBp = targetsBp[h.key]
                val delta =
                    if (targetBp == null || h.price <= 0) {
                        null
                    } else {
                        total * targetBp / FULL_BP / h.price - h.qty
                    }
                if (delta != null) spend += delta * h.price
                Line(h.key, h.evalAmt, weightBp(h.evalAmt, total), targetBp, delta)
            }
        val lines = holdingLines + Line(CASH, balance.cash, weightBp(balance.cash, total), targetsBp[CASH], null)
        val cost = balance.holdings.sumOf { it.qty * it.avgPrice }
        val pl = balance.holdings.sumOf { it.evalAmt } - cost
        return Plan(
            lines = lines,
            total = total,
            cashAfter = balance.cash - spend,
            targetSumBp = lines.sumOf { it.targetBp ?: 0 },
            totalPl = pl,
            totalPlRate = if (cost > 0) pl * PERCENT / cost else 0.0,
            duplicateKeys = balance.holdings.distinctBy { it.key }.size != balance.holdings.size,
        )
```

`CASH` 상수의 KDoc 도 한 줄 고친다 — 이제 종목코드가 아니라 신원과 겹치면 안 된다:

```kotlin
    /** 현금 행의 신원. 어떤 `종목코드|상품유형명` 과도 겹치지 않아야 한다. */
    const val CASH = "\$CASH"
```

- [ ] **Step 5: `HoldingDto` 가 새 필드를 읽게 한다**

`app/src/main/kotlin/dev/nhportfolio/api/NhApi.kt` 437-448행을 이렇게 바꾼다. 모든 새 필드에 기본값을 둔다 — 응답에 없어도 파싱이 죽으면 안 된다.

```kotlin
@Serializable
private data class HoldingDto(
    @SerialName("iem_cd") val code: String,
    @SerialName("iem_nm") val name: String = "",
    @SerialName("itg_bnc_qty") val qty: Double = 0.0,
    @SerialName("rsdl_qty") val remainQty: Double = 0.0,
    @SerialName("phs_pr") val avgPrice: Long = 0,
    @SerialName("now_pr") val price: Long = 0,
    @SerialName("eal_amt") val evalAmt: Long = 0,
    @SerialName("pft_rt") val pnlRate: Double = 0.0,
    // 신용/융자 구분. 상품유형명은 배지 문구로 그대로 쓰고, 대출 정보로 신용 여부를 판정한다.
    @SerialName("pdt_tp_nm") val productType: String = "",
    @SerialName("lon_bnc_amt") val loanAmt: Long = 0,
    @SerialName("lon_byn_dt") val loanDate: String = "",
) {
    fun toHolding() =
        Holding(
            code = code,
            name = name,
            qty = qty.toLong(),
            remainQty = remainQty.toLong(),
            avgPrice = avgPrice,
            price = price,
            evalAmt = evalAmt,
            pnlRate = pnlRate,
            productType = productType,
            loanAmt = loanAmt,
            loanDate = loanDate,
        )
}
```

- [ ] **Step 6: 파싱 테스트를 더한다**

`app/src/test/kotlin/dev/nhportfolio/NhApiTest.kt` 에, 그 파일이 이미 쓰는 잔고 응답 픽스처/헬퍼와 같은 방식으로 두 개를 더한다. 픽스처 만드는 법은 그 파일의 기존 잔고 테스트를 그대로 따른다.

- 잔고 응답에 `pdt_tp_nm`/`lon_bnc_amt`/`lon_byn_dt` 가 있으면 `Holding.productType`/`loanAmt`/`loanDate` 에 실려 오고 `onCredit` 이 true 다.
- **세 필드가 통째로 없어도** 파싱이 성공하고 `productType == ""`, `onCredit == false` 다. (NH 가 필드를 빼고 줄 수 있다 — 기본값이 그걸 받는다.)

- [ ] **Step 7: 기존 테스트의 `Line.code` 를 `key` 로 맞춘다**

`RebalanceTest.kt` 에서 `Line` 을 이름 붙은 인자로 만들거나 `.code` 를 읽는 곳을 전부 `key` 로 바꾼다. 기계적 변경이며 **로직을 바꾸지 않는다.** 바꾼 뒤 다음이 0건이어야 한다:

```bash
grep -n "Line(code\|\.code\b" app/src/test/kotlin/dev/nhportfolio/RebalanceTest.kt
```

(`Holding.code` 를 읽는 곳은 남아도 된다 — 종목코드는 여전히 존재한다. 위 grep 결과를 보고 `Line` 관련만 고친다.)

- [ ] **Step 8: 게이트를 돌린다**

```
./gradlew assembleDebug detekt ktlintCheck testDebugUnitTest
```
기대: BUILD SUCCESSFUL · **114개보다 많은** 테스트가 전부 통과 · detekt 0 · ktlint 0. 새 테스트 수를 보고한다.

`PortfolioScreen.kt` 가 `line.code`/`cashCodes` 를 쓰고 있어 컴파일이 깨진다 — Task 3 이 고친다. **이 태스크에서는 컴파일이 통과할 만큼만** `PortfolioScreen.kt` 를 기계적으로 맞춘다: `line.code` → `line.key`, `foldCash(it, cashCodes)` 의 인자 이름, `byCode`/`associateBy { it.code }` → `associateBy { it.key }`, `currentWeightsBp` 의 `it.code` → `it.key`. **표시(배지)는 건드리지 않는다** — Task 3 의 일이다.

- [ ] **Step 9: 커밋**

```bash
git add app/src/main/kotlin/dev/nhportfolio/model/Model.kt \
        app/src/main/kotlin/dev/nhportfolio/api/NhApi.kt \
        app/src/main/kotlin/dev/nhportfolio/portfolio/Rebalance.kt \
        app/src/main/kotlin/dev/nhportfolio/portfolio/PortfolioScreen.kt \
        app/src/test/kotlin/dev/nhportfolio/RebalanceTest.kt \
        app/src/test/kotlin/dev/nhportfolio/NhApiTest.kt
git commit -m "feat(model): 줄의 신원을 종목코드에서 종목코드+상품유형으로 옮김"
```

---

## Task 2: 저장된 목표와 현금성 지정을 초기화한다

사용자가 "전부 지우고 다시 잡기" 를 골랐다. 종목코드로 저장된 목표를 새 신원에 그대로 두면 어느 줄 것인지 알 수 없다.

**Files:**
- Modify: `app/src/main/kotlin/dev/nhportfolio/store/Prefs.kt`
- Modify: `app/src/test/kotlin/dev/nhportfolio/` — 새 파일 `PrefsTest.kt`

**Interfaces (Produces):** `targetsKey`/`cashKey` 의 시그니처는 그대로. 접두사만 바뀐다. `clearLegacyKeys(prefs: MutablePreferences, acctNo: String)` 추가.

- [ ] **Step 1: 접두사를 올리고 옛 키를 지우는 함수를 더한다**

`app/src/main/kotlin/dev/nhportfolio/store/Prefs.kt` 에서 두 함수의 접두사를 바꾸고, 아래를 더한다. `nameKey` 는 **그대로 둔다** — 계좌 이름은 종목 신원과 무관하다.

```kotlin
fun targetsKey(acctNo: String): Preferences.Key<String> = accountKey("targets2_", acctNo)

fun cashKey(acctNo: String): Preferences.Key<String> = accountKey("cash2_", acctNo)
```

그리고 파일 아래쪽에:

```kotlin
/**
 * 종목코드로 저장하던 옛 목표·현금성 지정을 지운다.
 *
 * 신원이 `종목코드`에서 `종목코드|상품유형명`으로 바뀌어서 옛 값은 어느 줄 것인지 알 수 없다.
 * 접두사만 올리고 두면 DataStore 에 영원히 남으므로 실제로 지운다.
 */
fun clearLegacyKeys(
    prefs: MutablePreferences,
    acctNo: String,
) {
    prefs.remove(accountKey("targets_", acctNo))
    prefs.remove(accountKey("cash_", acctNo))
}
```

import 를 하나 더한다 (알파벳 순서 유지):

```kotlin
import androidx.datastore.preferences.core.MutablePreferences
```

- [ ] **Step 2: 화면이 열릴 때 한 번 지우게 한다**

`app/src/main/kotlin/dev/nhportfolio/portfolio/PortfolioScreen.kt` 의 `PortfolioViewModel` 에는 지금 `init` 블록이 **없다.** 프로퍼티 선언 뒤, `ui` 선언 앞에 새로 만든다:

```kotlin
    init {
        // 신원이 바뀌어 옛 목표는 어느 줄 것인지 알 수 없다 — 한 번 지우고 새로 잡게 한다.
        viewModelScope.launch { store.edit { clearLegacyKeys(it, acctNo) } }
    }
```

`acctNo` 는 `val` 없는 생성자 파라미터인데, 코틀린에서 생성자 파라미터는 `init` 블록에서 그대로 보인다 — 시그니처를 바꾸지 않는다.

`import dev.nhportfolio.store.clearLegacyKeys` 를 알파벳 순서에 맞게 더한다.

- [ ] **Step 3: 테스트를 더한다**

`app/src/test/kotlin/dev/nhportfolio/PrefsTest.kt` 를 새로 만든다. 패키지 `dev.nhportfolio`, `kotlin.test` 사용.

- `targetsKey("111")` 와 `cashKey("111")` 의 이름이 서로 다르고, 둘 다 계좌번호 `111` 을 **포함하지 않는다** (해시 규칙 회귀 방지).
- `targetsKey("111")` 과 `targetsKey("222")` 가 다르다.
- `readTargets` 가 깨진 JSON 에 빈 맵을, 범위 밖 값(예: `-1`, `10001`)을 걸러낸 맵을 준다.
- `readCashCodes` 가 깨진 JSON 에 빈 집합을 준다.

`clearLegacyKeys` 는 `MutablePreferences` 가 필요해 순수 단위 테스트로 만들기 번거롭다 — **테스트하지 않는다.** 대신 Step 4 에서 기기로 확인한다.

- [ ] **Step 4: 게이트를 돌리고 기기에서 초기화를 확인한다**

```
./gradlew assembleDebug detekt ktlintCheck testDebugUnitTest
```

그리고 기기가 붙어 있으면 `./gradlew installDebug`. **앱을 지우지 말고 덮어 설치해야** 옛 값이 남아 있는 상태에서 초기화가 도는지 볼 수 있다. 계좌를 열어 목표 비중이 모두 "미설정" 으로 비었는지 확인한다. 기기가 없으면 **미검증** 으로 보고한다.

- [ ] **Step 5: 커밋**

```bash
git add app/src/main/kotlin/dev/nhportfolio/store/Prefs.kt \
        app/src/main/kotlin/dev/nhportfolio/portfolio/PortfolioScreen.kt \
        app/src/test/kotlin/dev/nhportfolio/PrefsTest.kt
git commit -m "feat(store): 신원 변경에 맞춰 저장된 목표·현금성 지정을 초기화"
```

---

## Task 3: 화면 — 신용/융자 배지와 줄 단위 선택

**Files:**
- Modify: `app/src/main/kotlin/dev/nhportfolio/portfolio/PortfolioScreen.kt`
- Modify: `app/src/main/kotlin/dev/nhportfolio/ui/Theme.kt` (배지 색)
- Modify: `app/src/main/kotlin/dev/nhportfolio/ui/Format.kt` (배지 색 접근자)

**Interfaces (Consumes):** Task 1 의 `Holding.key`/`onCredit`/`productType`, `Rebalance.Line.key`, `Plan.duplicateKeys`.

- [ ] **Step 1: 배지 색을 정의한다**

`app/src/main/kotlin/dev/nhportfolio/ui/Theme.kt` 의 색 정의부에, 기존 색 상수들과 같은 자리에 더한다. 손익(빨강·파랑)·조작(모스 초록)·구성 파이(인디고·앰버)가 이미 쓰는 색을 피해 **중성 회갈색**을 쓴다 — 배지는 강조가 아니라 분류다.

```kotlin
/** 신용/융자 배지. 손익·조작·구성 파이가 쓰는 색을 모두 피한 중성색 — 배지는 경고가 아니라 분류다. */
private val CreditInk = Color(0xFF6A5B4B)
private val CreditSurface = Color(0xFFF2EEE9)
private val CreditInkDark = Color(0xFFC8B69F)
private val CreditSurfaceDark = Color(0xFF2A241E)
```

`app/src/main/kotlin/dev/nhportfolio/ui/Format.kt` 의 `deltaChipColors`(55행) 바로 아래에 같은 모양으로 더한다. 그 파일은 다크 판정을 `onDark()` 헬퍼로 하고 `ChipColors(ink, surface)` 를 돌려준다 — 그대로 따른다:

```kotlin
/** 신용/융자 배지 색. 분류 표시이므로 손익·조작 색을 쓰지 않는다. */
@Composable
fun creditChipColors(): ChipColors =
    if (onDark()) ChipColors(CreditInkDark, CreditSurfaceDark) else ChipColors(CreditInk, CreditSurface)
```

- [ ] **Step 2: 배지를 그린다**

`PortfolioScreen.kt` 의 `HoldingRow` 안, 종목명 `Text` 바로 오른쪽에 온다. 이름과 금액이 `SpaceBetween` 인 `Row` 이므로, 이름 쪽을 작은 `Row` 로 감싸 배지를 이름 옆에 붙인다:

```kotlin
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false),
                ) {
                    Text(holding?.name ?: cashLabel, style = MaterialTheme.typography.titleMedium)
                    // 신용/융자 줄에만. 문구는 NH 가 준 상품유형명을 그대로 쓴다 —
                    // 우리가 지어낸 말보다 실제와 어긋날 위험이 없다.
                    if (holding?.onCredit == true) CreditChip(holding.productType)
                }
                Text(line.currentAmt.krw(), style = MaterialTheme.typography.titleMedium)
            }
```

그리고 `DeltaChip` 아래에 같은 모양으로:

```kotlin
/** 신용/융자 배지. 문구가 비어 있으면 "신용" 으로 대신한다 — 빈 칩은 뜻이 없다. */
@Composable
private fun CreditChip(productType: String) {
    val c = creditChipColors()
    Text(
        text = productType.takeIf { it.isNotBlank() } ?: "신용",
        style = MaterialTheme.typography.labelSmall,
        color = c.ink,
        modifier =
            Modifier
                .background(c.surface, MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
```

- [ ] **Step 3: 중복 신원 경고를 띄운다**

`PlanWarnings` 안, 예수금 부족 경고와 같은 자리에 더한다:

```kotlin
        if (plan.duplicateKeys) {
            Text(
                "같은 종목이 구분 없이 두 번 왔습니다 — 목표 비중이 두 줄에 겹쳐 매매 수량이 부풀 수 있습니다",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
```

`PlanWarnings` 가 detekt 복잡도에 걸리면 **추출로 푼다.**

- [ ] **Step 4: 남은 `code` 사용처를 점검한다**

Task 1 Step 8 이 기계적으로 맞춰 놓았으므로, 여기서는 **의미가 맞는지** 본다:

```bash
grep -n "\.code\b\|cashCodes\|byCode" app/src/main/kotlin/dev/nhportfolio/portfolio/PortfolioScreen.kt
```

남아도 되는 것은 `holding.code`(종목코드를 정말 종목코드로 쓰는 자리)뿐이다. 선택·목표·현금성 지정·조회는 전부 `key` 여야 한다. `PortfolioUi.cashCodes` 프로퍼티 이름도 `cashKeys` 로 바꾼다 — 이름이 내용과 어긋나면 안 된다.

- [ ] **Step 5: 게이트를 돌리고 기기에서 확인한다**

```
./gradlew assembleDebug detekt ktlintCheck testDebugUnitTest
```

기기가 있으면 설치하고 실행·크래시만 확인한다. **화면을 눌러야 아는 항목은 미검증으로 보고한다** — 서브에이전트는 탭할 수 없고, 포트폴리오 화면은 NH 로그인이 필요하다.

- [ ] **Step 6: 커밋**

```bash
git add app/src/main/kotlin/dev/nhportfolio/portfolio/PortfolioScreen.kt \
        app/src/main/kotlin/dev/nhportfolio/ui/Theme.kt \
        app/src/main/kotlin/dev/nhportfolio/ui/Format.kt
git commit -m "feat(ui): 신용/융자 배지 표시와 줄 단위 선택"
```

---

## Task 4: 시안에 배지와 분리된 두 줄을 반영

**Files:**
- Modify: `design/Main.dc.html`
- Modify: `design/Selecting.dc.html`

**Interfaces (Consumes):** Task 3 이 확정한 배지 모양(이름 오른쪽, 중성 회갈색, NH 상품유형명 그대로).

- [ ] **Step 1: 표본 데이터에 같은 종목의 두 줄을 넣는다**

두 파일의 `renderVals()` 에서 삼성전자 줄을 **두 줄로 가른다** — 현금분과 신용분. 두 줄이 같은 이름을 갖고 한쪽에만 배지가 붙는 모습이 이 기능의 요점이다. 금액·수량·평균가는 서로 다르게 두어 두 줄이 독립임이 보이게 한다.

`Main.dc.html` 의 행 마크업에서 이름 `<span>` 옆에 배지를 그린다:

```html
        <span style="font-size: 15px; font-weight: 700;">{{r.name}}</span>
```

를 이렇게 바꾼다:

```html
        <span style="display: flex; align-items: center; gap: 6px; min-width: 0;">
          <span style="font-size: 15px; font-weight: 700;">{{r.name}}</span>
          <sc-if value="{{r.credit}}" hint-placeholder-val="{{ false }}">
            <span style="font-size: 10px; font-weight: 700; color: #6A5B4B; background: #F2EEE9; padding: 2px 6px; border-radius: 5px; white-space: nowrap;">{{r.creditLabel}}</span>
          </sc-if>
        </span>
```

`Selecting.dc.html` 도 같은 자리에 같은 마크업을 넣는다.

- [ ] **Step 2: 두 시안이 코드와 맞는지 대조한다**

| 항목 | 코드 | 시안 |
|---|---|---|
| 배지 위치 | 종목명 오른쪽 | 종목명 오른쪽 |
| 배지 색 | `#6A5B4B` / `#F2EEE9` | 같은 값 |
| 배지 문구 | `pdt_tp_nm` 그대로 | 표본에 NH 스러운 상품유형명 |
| 배지 조건 | 대출잔고 있는 줄만 | 신용분 줄에만 |
| 같은 종목 두 줄 | 각각 독립된 목표·비중 | 두 줄의 목표가 서로 다름 |

다섯 줄이 모두 맞아야 끝난다.

- [ ] **Step 3: 시안이 여전히 파싱되는지 확인한다**

```bash
python -c "import json; d=json.load(open('design/canvas.json', encoding='utf-8')); print(len(d['artboards']), len(d['annotations']))"
```
기대: `10 4` (이 태스크는 `canvas.json` 을 건드리지 않는다).

두 `.dc.html` 의 `<script data-dc-script>` 블록이 유효한 JavaScript 인지 확인한다.

- [ ] **Step 4: 커밋**

```bash
git add design/Main.dc.html design/Selecting.dc.html
git commit -m "design: 신용/융자 배지와 같은 종목 두 줄을 시안에 반영"
```

---

## 완료 기준

1. 게이트 통과, 테스트 수가 114개보다 많고 전부 통과.
2. `Rebalance.plan` 이 같은 종목코드의 현금분·신용분에 **목표를 따로** 건다 (테스트로 증명).
3. 목표 합계가 중복으로 두 번 세어지지 않는다 (테스트로 증명).
4. 신원이 겹치면 화면에 경고가 뜬다.
5. 신용/융자 줄에 NH 가 준 상품유형명 배지가 붙는다.
6. 선택·목표·현금성 지정이 전부 `key` 로 돈다 — `PortfolioScreen.kt` 에 남은 `.code` 는 종목코드를 정말 종목코드로 쓰는 자리뿐.
7. 저장돼 있던 옛 목표·현금성 지정이 지워진다.
8. 시안 대조표 5줄이 코드와 맞는다.
9. `git push` 는 하지 않았다.

## 남는 위험

- **실제 필드값을 아직 못 봤다.** `lon_bnc_amt`/`lon_byn_dt` 가 실제로 채워져 오는지, 현금분의 `pdt_tp_nm` 이 무엇인지는 실계좌 응답을 봐야 안다. 배지가 하나도 안 붙거나 전부 붙으면 D3 의 판정식을 한 줄 고치면 된다 — 구조는 그대로다.
- **대출 건이 여럿이면 여전히 겹칠 수 있다.** 같은 `(종목코드, 상품유형명)` 두 줄은 D1 이 가르지 못한다. D2 의 경고가 그걸 알린다.
- **고아 목표는 이 계획이 고치지 않는다** (D7). 보유에서 사라진 키에 남은 목표를 `normalize` 가 계속 스케일한다 — 선행 문제이고 D5 의 초기화로 시작 시점에는 0이다.
- **회전 시 선택이 사라진다** — 선행 동작, 이 계획과 무관.

## 자체 검토

**요구 커버리지**

| 요구 | 태스크 |
|---|---|
| 신용/융자 표시 | Task 3 Step 1·2, Task 4 |
| 같은 종목의 두 줄을 별도 선택 | Task 1 Step 3·4 (신원), Task 3 Step 4 (화면) |
| (파생) 목표 비중도 분리 — 사용자 확정 | Task 1 Step 1·4 |
| (파생) 저장된 목표 초기화 — 사용자 확정 | Task 2 |

**시그니처 일관성**

| 심볼 | 정의 | 사용 |
|---|---|---|
| `Holding.key` / `onCredit` / `productType` | T1 Step 3 | T1 Step 4·5, T3 Step 2·4 |
| `Rebalance.Line.key` | T1 Step 4 | T1 Step 7, T3 Step 4 |
| `Plan.duplicateKeys` | T1 Step 4 | T3 Step 3 |
| `foldCash(balance, cashKeys)` | T1 Step 4 | T1 Step 8 (기계적), T3 Step 4 |
| `clearLegacyKeys(prefs, acctNo)` | T2 Step 1 | T2 Step 2 |
| `creditChipColors()` | T3 Step 1 | T3 Step 2 |

**태스크 경계에서 빌드가 통과하는가**

- T1 끝: `PortfolioScreen.kt` 를 컴파일 통과선까지 기계적으로 맞춘다(Step 8) → 통과
- T2 끝: Prefs 접두사 + init 블록, 나머지 무관 → 통과
- T3 끝: 표시층만 추가 → 통과
- T4: 코틀린 무변경 → 영향 없음
