# 시장 상황에 따른 주식 비중 관리 — 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 코스피 대형주의 시장폭(이동평균 상회 비율)을 3년 백분위로 환산해 주식 편입비중
목표를 산출하고, 목표와 현재 비중의 차이가 15%p 이상일 때만 실행하도록 안내한다. 산출된
목표는 기존 목표 비중 화면에 그대로 반영된다. 거래 기능은 여전히 없다.

**Spec:** `docs/superpowers/specs/2026-09-05-market-exposure-model.md` — 계획은 사양을 근거로
삼는다. 실행자는 둘 다 읽는다. 아래에서 "사양 §N" 은 그 문서의 절 번호다.
모델의 원본 근거는 `docs/manage/` 의 네 파일이다.

**Architecture:** 기존 경계를 바꾸지 않는다. `api/NhApi.kt` 는 여전히 NH 를 아는 유일한
파일이고, 새 계산 코드는 네트워크를 모르는 순수 함수이며, 화면은 NH 필드명을 모른다.
새 패키지 `market/` 하나가 늘고 기존 파일은 `NhApi.kt`, `PortfolioScreen.kt`, `store/Prefs.kt`
세 개만 손댄다. Repository·UseCase·인터페이스는 만들지 않는다.

**이전 계획과 문서 형식이 다른 점:** `2026-08-30-nh-portfolio.md` 는 각 단계에 완성 코드를
그대로 실었다. 이 계획은 시그니처와 알고리즘, 테스트 목록, 검증 기준까지만 적는다. 구현
코드를 계획에 미리 박아 두면 실제 코드와 갈라져 두 개의 진실이 생기고, 이 기능은 기존
코드와의 결합이 얕아 그럴 필요가 없다. 판단이 필요한 곳은 알고리즘과 함정을 문장으로 적는다.

---

## Global Constraints

`2026-08-30-nh-portfolio.md` 의 Global Constraints 를 그대로 승계한다. 이 기능에서 특히
중요한 것과 새로 추가되는 것만 다시 적는다.

- **언어·패키지·SDK**: 기존과 동일 (Kotlin only, `dev.nhportfolio`, compileSdk/targetSdk 37, minSdk 31).
- **비밀 취급**: 앱키·시크릿·토큰을 로그·화면·예외 메시지에 절대 넣지 않는다. 이 기능은
  새 로그를 **하나도** 추가하지 않는다.
- **NH API 규칙**: 토큰 24시간 캐시, 재발급은 401 이면서 발급 1시간이 지난 경우에만.
  **429 는 지연만 하고 토큰을 건드리지 않는다.** `cts`/`cts_flag` 는 응답 헤더에서 읽는다.
  HTTP 200 은 성공이 아니다. 이 모두는 기존 `call()`/`pages()` 가 이미 처리한다.
- **호출 간격 (신규)**: 종목 단위 반복 호출은 **0.25초 이상** 간격을 둔다. 명세의 권고는
  0.2초이며, 200회를 연달아 보내는 유일한 코드이므로 여유를 둔다.
- **금액·수량·비중 단위**: 금액 KRW `Long`, 수량 `Long`, 비중은 basis point `Int`
  (1250 = 12.50%). 시장폭·백분위 같은 통계 중간값만 `Double` 을 쓰고, 화면에 나가는
  비중은 반드시 bp `Int` 로 환산한 뒤 내보낸다.
- **모르면 지어내지 않는다 (신규, 사양 §2.2)**: 관측이 모자라 백분위를 정의할 수 없으면
  중립값으로 채우지 않고 "아직 계산할 수 없습니다" 를 표시한다. 부분 창(252~755개)은 원
  코드의 정의라 계산하되 "백분위 창 N/756일" 로 드러낸다.
- **반올림은 `kotlin.math.round` 만 쓴다 (신규, 사양 §2.1)**: `roundToInt()`·`Math.round()`
  는 0.5 를 올려 `np.round` 와 어긋난다. `market/` 패키지에 이 둘이 보이면 반려한다.
- **커밋**: 태스크마다 마지막 단계에서 커밋한다. 원격 푸시는 하지 않는다. 메시지 끝에
  다음 두 줄을 붙인다.
  ```
  Co-Authored-By: Claude Opus 5 (1M context) <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01WxcKt3FgEABem3fvr5SHqA
  ```
- **검증**: 완료로 표시하기 전에 그 태스크의 검증 명령을 실제로 실행하고 출력을 확인한다.
  통과 주장은 근거가 아니다.

## 확정 사항 (2026-09-05 사용자 확인)

1. **유니버스는 KODEX 200(069500) 의 구성종목**이다. KIS OpenAPI 는 쓰지 않는다.
2. **1차 범위는 판정 + 목표 적용 + 밴드 지침**이다. 3영업일 3회 분할 실행 추적, -8% 손절
   배지, 연 1회 폐기 조건 점검은 범위 밖이다.
3. **`riskmodel.py` 원 코드를 확보해 사양 §2 에 반영했다.** 구성별 양자화가 두 번, 반올림은
   은행가 반올림(`kotlin.math.round`, `roundToInt` 금지), 백분위는 `min_periods=252` 의 부분
   창 허용, 동점은 평균 순위, 판정의 비교 대상은 유지 비중(`100% − 예수금 목표`)이다.
   최종 모델은 여섯 신호 중 `breadth` 하나만 쓴다.

## 파일 구조

| 파일 | 책임 | Task |
|---|---|---|
| `market/Breadth.kt` | 순수 계산. 종가 배열 → 수정주가 보정 → 시장폭 → 백분위 → 평활 → 앙상블 → 목표·밴드·판정. 라이브러리 import 0개 | 1 |
| `api/NhApi.kt` | `etfComponents()`, `dailyBars()` 두 메서드와 private DTO 추가 | 2 |
| `market/MarketData.kt` | 유니버스·종가 캐시 파일 하나, 동기화 오케스트레이션, 진행률, 재개 | 3 |
| `market/MarketCard.kt` | 포트폴리오 화면 상단 카드 | 4 |
| `portfolio/PortfolioScreen.kt` | 카드 배치, `applyMarketTarget()` | 4 |
| `market/BandGuide.kt` | 밴드별 지침 정적 데이터와 지침 화면 | 5 |
| `MainActivity.kt` | 지침 화면 라우트 1개 추가 | 5 |
| `README.md` | 스모크 체크리스트·사양 목록 갱신 | 6 |

---

## Task 1: 순수 계산 — `market/Breadth.kt`

이 기능에서 틀리면 사용자가 잘못된 금액으로 매매하게 되는 유일한 곳이다. 네트워크도
안드로이드도 모르는 순수 코드로 격리하고, 테스트를 사양 §2 와 1:1로 붙인다.

**Files:**
- Create: `app/src/main/kotlin/dev/nhportfolio/market/Breadth.kt`
- Test: `app/src/test/kotlin/dev/nhportfolio/BreadthTest.kt`

**Interfaces:**
- Consumes: 없음. 어떤 라이브러리도 import 하지 않는다(detekt `ForbiddenImport` 가 강제).
- Produces:
  ```kotlin
  /** 일봉 한 줄. NH 필드명을 모르는 시장 무관 타입이다.
   *  [refPrice] 는 기준가, [exRight] 는 액면분할·병합·권리락이 있었던 날인지. */
  data class Bar(val date: String, val close: Int, val refPrice: Int, val exRight: Boolean)

  enum class Band { MAX_DEFENSE, DEFENSE, NEUTRAL, ACTIVE, MAX_INVEST }
  enum class Action { HOLD, CUT, ADD }

  data class Signal(
      val targetBp: Int,       // 0, 1250, 2500, ... 10000
      val band: Band,
      val breadth: Double,     // 200일선 상회 비율 (표시용)
      val pctile: Double,      // MA200 의 3년 백분위, 평활 전 (표시용)
      val window: Int,         // 백분위 창에 든 관측 수. 756 미만이면 부분 창 (사양 §2.2)
      val asOf: String,        // 기준 일자 YYYYMMDD
  )

  data class Verdict(val action: Action, val gapBp: Int)

  object Breadth {
      const val PCT_WIN = 756
      const val PCT_MIN = 252
      fun adjust(bars: List<Bar>): IntArray                 // 수정주가 보정된 종가 (오래된 것부터)
      fun signal(closes: Map<String, IntArray>, dates: List<String>): Signal?   // 부족하면 null
      fun bandOf(targetBp: Int): Band
      /** [heldBp] 는 유지 비중 — 마지막으로 적용한 목표(사양 §2.3). 실제 비중이 아니다. */
      fun verdict(targetBp: Int, heldBp: Int): Verdict
  }
  ```

  `closes` 의 배열은 `dates` 와 길이가 같고, 상장 전이라 값이 없는 날은 `0` 이다(주가는 0 이
  될 수 없으므로 안전한 빈칸이다).

**알고리즘 메모 (구현자가 판단해야 하는 곳):**

- `adjust` 는 **최신에서 과거 방향**으로 훑는다. `exRight` 인 날 `t` 에서
  `factor = refPrice(t) / close(t−1)` 을 구하고, `|factor − 1| > 0.02` 일 때만 `t` **이전**의
  모든 종가에 누적 곱한다. 응답이 이미 수정주가면 `factor` 가 1 에 붙어 저절로 통과한다
  (사양 §6.1). 최종 결과는 반올림하여 `Int` 로 되돌린다.
- 이동평균은 **이동합(prefix sum)** 으로 계산한다. 200종목 × 1,100일 × 3길이를 매번 다시
  계산하므로, 창마다 다시 더하면 O(n·m) 이 되어 체감할 만큼 느려진다.
- `min_periods` 는 `floor(m × 0.75)` 다. 원 코드의 `int(ma * .75)` 와 같다.
- 시장폭의 분모는 **종가와 이동평균이 둘 다 정의된 종목 수**이며, 30 미만이면 그 날을
  미정의로 둔다. 이 두 규칙을 빠뜨리면 상장 초기 종목이 전부 "이동평균 위" 로 잡혀
  시장폭이 부풀려진다(사양 §6.2).
- 백분위는 `rolling(756, min_periods=252).rank(pct=True)` 다. 최근 756개 행 중 정의된 관측
  `n` 개를 세고, `n < 252` 면 미정의, 아니면
  `(작은 관측 수 + (같은 관측 수 + 1) / 2) / n` 이다. **동점은 평균 순위**다(사양 §2 의 2).
  창마다 정렬하면 756 × log 이 1,100번이라 문제없다 — 영리한 자료구조는 쓰지 않는다.
- 평활은 최근 `sm` 개 백분위의 평균이며 `sm` 개가 전부 정의되어 있을 때만 정의된다.
- **양자화는 두 번이다.** 구성마다 `round(smooth × 8) / 8` 을 먼저 하고, 9개를 평균한 뒤
  다시 `round(ens × 8) / 8` 을 한다(사양 §2 의 4·6). 한 번만 하면 결과가 달라진다.
- **`round` 는 `kotlin.math.round(Double)` 이다.** 0.5 를 짝수로 보내는 은행가 반올림이라
  `np.round` 와 같다. `roundToInt()` 는 0.5 를 올리므로 **금지**한다(사양 §2.1). 이 파일에
  `roundToInt` 가 등장하면 리뷰에서 반려한다.
- 밴드 경계는 bp 로 `[0,1300) [1300,4400) [4400,5600) [5600,8150) [8150,10000]` 이다.
  경계값 자체가 어느 쪽에 속하는지 테스트로 못 박는다.
- 판정 임계는 `1500` bp 이며 **절대값 비교**다. 같은 값(정확히 15%p)은 실행 쪽이다
  (`backtest()` 의 `>= band`). 비교 대상 `heldBp` 는 호출자가 넘기며, 이 함수는 그것이
  유지 비중인지 실제 비중인지 모른다.

**Steps:**

- [ ] **Step 1: 실패하는 테스트 작성** — `BreadthTest.kt`. 아래를 각각 독립된 테스트로 만든다.
  - 수정주가 보정: 2:1 분할 시뮬레이션에서 분할 이전 종가가 절반이 된다.
  - 수정주가 보정: 응답이 **이미 수정주가**면 `adjust` 가 원본을 그대로 돌려준다.
  - 수정주가 보정: `exRight` 가 아닌 날의 큰 가격 변동에는 손대지 않는다.
  - 수정주가 보정: 배당락만 있는 날(`exRight = false`)은 보정하지 않는다.
  - 이동평균: 유효 관측이 `floor(m × 0.75)` 미만인 종목은 그 날 분모에서 빠진다.
  - 시장폭: 유효 종목이 30 미만인 날은 미정의이며 신호가 `null` 이 된다.
  - 백분위: 관측 251개면 `null`, 252개면 값이 나오고 `window == 252` 다.
  - 백분위: 관측 1,000개면 최근 756개만 쓰고 `window == 756` 이다.
  - 백분위: `riskmodel_functions.md` 의 예시 — `[10,20,30,40,50,5]` 에 창 5 를 걸면 마지막
    값은 `0.2` 다(테스트용으로 `PCT_WIN`·`PCT_MIN` 을 인자로 받게 한다).
  - 백분위: 최솟값의 백분위는 `1/n` 이지 0 이 아니다.
  - 백분위: 동점 — `[1,2,2]` 의 마지막 값은 `2.5/3` 이다(평균 순위). `[2,2,2]` 는 `2/3` 이다.
  - 반올림: 0.0624 → 0, **0.0625 → 0**, 0.0626 → 1250, 0.1875 → 2500, **0.3125 → 2500**
    (사양 §2.1 표). `roundToInt` 를 쓰면 굵은 두 값이 틀린다.
  - 양자화 두 번: 구성 5개가 0.19, 4개가 0.17 이면 목표는 **2500** 이다. 평활값을 바로
    평균해 한 번만 반올림하면 1250 이 나온다 — 그 오답을 주석에 적어 둔다.
  - 앙상블: 백분위 0.10 이 목표 1250bp 가 된다(보고서 예시). 0.80 은 7500bp 다.
  - 밴드 경계 5개를 각각 양쪽 값으로 확인한다(1299/1300, 4399/4400, 5599/5600, 8149/8150).
  - 판정: 차이 1499bp 는 HOLD, 1500bp 는 실행, 목표가 낮으면 CUT, 높으면 ADD.
  - 판정: 유지 비중 2500 에 목표 1250 은 HOLD(가이드북 2026-09-04 사례), 목표 0 은 CUT.
  - 판정: 비중이 0 이거나 10000 인 극단에서도 예외가 나지 않는다.
  - 골든 벡터: 알려진 종가 배열(고정 시드로 생성한 소형 유니버스)에서 목표 비중이
    특정 값으로 나온다. 이후 리팩터링이 결과를 바꾸면 이 테스트가 잡는다.
- [ ] **Step 2: 테스트가 실패하는지 확인** — `./gradlew testDebugUnitTest --no-daemon`.
      기대: `Unresolved reference: market`.
- [ ] **Step 3: `Breadth.kt` 작성.** 위 알고리즘 메모대로 구현한다.
- [ ] **Step 4: 검증** — `./gradlew testDebugUnitTest detekt ktlintCheck --no-daemon`.
      전 테스트 통과, detekt·ktlint 통과. `ForbiddenImport` 가 이 파일에도 걸리는지
      `import android.util.Log` 를 잠시 넣어 실패하는 것을 확인하고 되돌린다.
- [ ] **Step 5: 커밋** — `feat(market): 시장폭 익스포저 모델 순수 계산`

---

## Task 2: NH API 확장 — `etfComponents`, `dailyBars`

**Files:**
- Modify: `app/src/main/kotlin/dev/nhportfolio/api/NhApi.kt`
- Test: `app/src/test/kotlin/dev/nhportfolio/NhApiTest.kt` (기존 파일에 추가)

**Interfaces:**
```kotlin
/** ETF 구성종목의 종목코드. 유니버스 조달 경로다(krstock 에 랭킹 API 가 없다). */
suspend fun NhApi.etfComponents(etfCode: String): List<String>

/** 일봉. [count] 는 읽을 건수, [endDate] 는 YYYYMMDD(비우면 최근). 오래된 것부터 정렬해 돌려준다. */
suspend fun NhApi.dailyBars(code: String, count: Int, endDate: String = ""): List<Bar>
```

**구현 메모:**

- 두 메서드 모두 기존 `pages()` 를 그대로 쓴다. `cts` 연속조회·401 재발급·429 지연·
  성공 판정이 전부 거기 있으므로 새로 만들 것이 없다.
- `etfComponents` 입력은 `Input_0.iem_cd` 하나다. 응답 `Output_0` 이 구성종목 배열이다.
- `dailyBars` 입력은 `market_cd = "UNT"`(통합시세, 기존 `balance()` 의 `qut_dit_cd` 와 같은
  기준), `iem_cd`, `mrkt_div_cls_code = "1"`(거래소), `gubun = "1"`(일), `edate`, `array_cnt`.
- **응답에서 쓰는 것은 `Output_1` 뿐이다.** `Output_0` 은 명세상 Array 인데 예시는 Object 라는
  경고가 붙어 있으므로 기존 `accounts()` 처럼 `JsonElement` 로 받아 넘긴다(사양 §6.4).
- DTO 는 전부 `private` 이며 숫자 필드는 **`String` 으로 받는다.** NH 는 고정폭 필드라
  빈 문자열과 공백 채움이 온다. 기존 `lon_bnc_amt` 에서 이미 겪은 문제다.
- `Bar.exRight` 판정은 이 파일 안에서 끝낸다. `fcam_mod_cls_code ∈ {01,02,03,04}` 또는
  `flng_cls_code ∈ {01,04,06,07}` 이면 true 다. **NH 코드값을 아는 것은 이 파일뿐이라는
  기존 경계를 지키기 위해서다.**
- 응답이 오래된 것부터 오는지 최신부터 오는지 명세에 없다. `bsop_date` 로 **정렬해서**
  돌려준다. 순서를 가정하지 않는다.
- `array_cnt` 의 상한이 명세에 없다. 서버가 건수를 자르면서 `cts` 도 주지 않으면
  `pages()` 는 한 페이지로 끝난다. 그때는 **받은 것 중 가장 오래된 일자의 전날을 `edate`
  로 넣어 다시 부르는** 수동 페이징을 `dailyBars` 안에서 반복한다. 요청한 [count] 에
  닿거나 빈 응답이 오면 멈춘다.

**Steps:**

- [ ] **Step 1: 실패하는 테스트 작성** (MockEngine)
  - `etfComponents` 가 구성종목 코드 목록을 파싱한다.
  - `etfComponents` 가 `cts_flag=Y` 일 때 다음 페이지를 이어 받는다.
  - `dailyBars` 가 `Output_1` 을 `Bar` 로 옮기고 **일자 오름차순**으로 정렬한다.
  - `dailyBars` 가 최신부터 내려온 응답도 같은 결과로 정렬한다.
  - `dailyBars` 가 빈 문자열·공백 채움 숫자 필드에서 죽지 않는다.
  - `dailyBars` 가 `fcam_mod_cls_code = "01"` 을 `exRight = true` 로 옮긴다.
  - `dailyBars` 가 `flng_cls_code = "02"`(배당락) 을 `exRight = false` 로 둔다.
  - `dailyBars` 가 `cts` 없이 잘린 응답을 받으면 `edate` 를 옮겨 이어 받고, 요청 건수에
    닿으면 멈춘다.
  - 조회 0건(`Output_1` 없음, 정상 응답)이 빈 목록이고 예외가 아니다.
  - 업무 오류 응답이 `NhException` 이 되고 메시지에 `rsp_msg` 가 그대로 담긴다.
- [ ] **Step 2: 테스트 실패 확인**
- [ ] **Step 3: `NhApi.kt` 에 메서드와 DTO 추가**
- [ ] **Step 4: 검증** — `./gradlew testDebugUnitTest detekt ktlintCheck --no-daemon`
- [ ] **Step 5: 커밋** — `feat(api): ETF 구성종목과 일봉 조회 추가`

---

## Task 3: 캐시와 동기화 — `market/MarketData.kt`

200회 연속 호출을 다루는 유일한 코드다. 진행률, 중단 후 재개, 부분 실패, 호출 간격이
전부 여기 있다.

**Files:**
- Create: `app/src/main/kotlin/dev/nhportfolio/market/MarketData.kt`
- Test: `app/src/test/kotlin/dev/nhportfolio/MarketDataTest.kt`

**Interfaces:**
```kotlin
sealed interface SyncState {
    data object Idle : SyncState
    data class Running(val done: Int, val total: Int) : SyncState
    data class Failed(val message: String) : SyncState
}

class MarketData(private val api: NhApi, private val dir: File) {
    /** 캐시된 종가로 계산한 신호. 캐시가 없거나 모자라면 null. 네트워크를 타지 않는다. */
    fun cached(): Signal?
    /** 확보한 거래일 수. 신호가 null 일 때 "거래일 N, 최소 약 500일" 표시에 쓴다. */
    fun cachedDays(): Int
    /** 유니버스와 종가를 갱신한다. 진행률을 흘리고, 끝나면 새 신호를 돌려준다. */
    fun sync(): Flow<SyncState>
}
```

**구현 메모:**

- **저장은 `dir/closes.json` 파일 하나다.** DataStore 에 넣지 않는다. `edit {}` 한 번이
  파일 전체를 다시 쓰기 때문에, 1 MB 짜리 배열을 같이 두면 토큰 재발급 한 번이 매번 그
  전체를 다시 쓰게 된다(사양 §5.3).
- **봉인하지 않는다.** 공개 시장 데이터라 지킬 비밀이 없고, DEK 에 묶으면 저장 경로만
  좁아진다.
- 형식은 `{"asOf":"YYYYMMDD","universeAt":"YYYYMMDD","universe":[...],"dates":[...],"closes":{"005930":[...]}}`
  로 충분하다. 종가는 `Int` 배열이며 `dates` 와 길이가 같다. 유니버스도 같은 파일에 둔다 —
  파일을 둘로 나누면 한쪽만 깨졌을 때의 처리가 하나 더 생긴다.
- **"마지막 계산 시각" 을 따로 저장하지 않는다.** `asOf`(마지막 거래일)가 그 역할을 한다.
  화면의 "7일이 지나면 갱신 권고" 도 벽시계가 아니라 이 값을 기준으로 삼는다 — 주 1회
  신호에서 의미 있는 경과는 "언제 계산했는가" 가 아니라 "며칠 전 장까지 반영됐는가" 다.
- **쓰기는 임시 파일에 하고 `renameTo` 로 바꾼다.** 200회 호출 도중 앱이 죽으면 반쯤 쓰인
  JSON 이 남아 다음 실행에서 파싱이 깨진다.
- 읽기가 실패하면(형식 오류·부분 기록) **캐시가 없는 것으로 보고 조용히 다시 받는다.**
  화면이 죽어서는 안 된다. 기존 `readTargets` 의 `runCatching` 관용구와 같은 태도다.
- **재개**: 종목별로 이미 가진 마지막 일자를 보고, 필요한 만큼만 요청한다. 캐시가 없으면
  1,100건, 있으면 부족분 + 여유 5건이다. 중단되었다가 다시 부르면 이미 받은 종목은 건너뛴다.
- **부분 실패**: 종목 하나가 실패해도 나머지를 계속 받는다. 시장폭 계산이 유효 종목 30 하한을
  이미 갖고 있으므로 몇 종목이 빠져도 신호는 성립한다. 다만 **실패 종목 수를 표면에 낸다.**
- **호출 간격 0.25초.** `delay(250)` 을 종목 사이에 둔다.
- **취소**: `Flow` 가 취소되면 그 자리에서 멈추고 지금까지 받은 것을 저장한다. 화면을
  떠나면 취소되며, 다음에 이어받는다.
- 거래일 달력은 **유니버스 전체 일자의 합집합**이고, 종목마다 없는 날은 직전 값으로 채운다
  (사양 §6.3). 앞쪽에 값이 없는 구간(상장 전)은 채우지 않고 미정의로 둔다.
- 유니버스는 `universeAt` 이 **90일 이상** 지났을 때만 다시 받는다. 실패하면 캐시된
  목록을 그대로 쓴다.

**Steps:**

- [ ] **Step 1: 실패하는 테스트 작성** (MockEngine + `@TempDir`)
  - 캐시가 없으면 `cached()` 가 null 이고 `cachedDays()` 가 0 이다.
  - `sync()` 가 유니버스 1회 + 종목 수만큼 호출하고 진행률을 순서대로 흘린다.
  - 종목 하나가 실패해도 나머지가 저장되고 신호가 계산된다.
  - 저장 파일이 깨져 있으면 예외 없이 다시 받는다.
  - 중단 후 다시 부르면 이미 받은 종목을 다시 받지 않는다.
  - 종목마다 일자가 어긋난 응답에서 달력 합집합과 직전 값 채움이 맞다.
  - 상장 전 구간은 채우지 않아 그 종목이 분모에서 빠진다.
  - 유니버스 캐시가 90일 미만이면 `etfComponents` 를 호출하지 않는다.
  - 유니버스 조회가 실패하면 캐시된 목록으로 계속 진행한다.
  - 거래일이 약 500 미만이면 `cached()` 가 null 이고 `cachedDays()` 가 실제 수를 준다.
  - 거래일이 700 이면 신호가 나오고 `signal.window` 가 756 미만이다(부분 창).
- [ ] **Step 2: 테스트 실패 확인**
- [ ] **Step 3: `MarketData.kt` 작성**
- [ ] **Step 4: 검증** — `./gradlew testDebugUnitTest detekt ktlintCheck --no-daemon`
- [ ] **Step 5: 커밋** — `feat(market): 종가 캐시와 동기화`

---

## Task 4: 포트폴리오 카드와 목표 적용

**Files:**
- Create: `app/src/main/kotlin/dev/nhportfolio/market/MarketCard.kt`
- Modify: `app/src/main/kotlin/dev/nhportfolio/portfolio/PortfolioScreen.kt`
- Modify: `app/src/main/kotlin/dev/nhportfolio/App.kt` (Koin 에 `MarketData` 등록)
- Test: `app/src/test/kotlin/dev/nhportfolio/MarketTargetTest.kt`

**PortfolioUi 확장:**
```kotlin
data class PortfolioUi(
    // ... 기존 필드 그대로 ...
    val signal: Signal? = null,   // 기준 일자는 signal.asOf — 따로 들고 다니지 않는다
    val signalDays: Int = 0,
    val sync: SyncState = SyncState.Idle,
)
```

**PortfolioViewModel 추가:**
```kotlin
/** 모델 목표를 종목 목표에 반영한다. 예수금 목표를 100% − 목표로 잡으면
 *  종목끼리의 상대 비율은 그대로 두고 합계만 목표에 맞춰 비례 조정된다. */
fun applyMarketTarget(exposureBp: Int) {
    edit { Rebalance.scaleForCash(it, FULL_BP - exposureBp, currentWeightsBp()) }
}

fun syncMarket()   // MarketData.sync() 를 viewModelScope 에서 수집
```

**판정의 비교 대상은 유지 비중이다(사양 §2.3).** `heldBp = FULL_BP − targets[CASH]` 이며,
예수금 목표가 없으면 실제 주식 비중으로 대신하고 카드에 "적용한 목표가 없어 실제 비중과
비교합니다" 를 적는다. 실제 비중과 비교하면 드리프트만으로 검증된 것보다 두 배 가까이 자주
매매하게 되므로, **이 한 줄을 실제 비중으로 바꾸는 것은 버그다.** 주석으로 그렇게 밝힌다.

**실제 주식 비중의 정의(참고 표시용):** `plan.lines` 에서 현금 행을 뺀 `weightBp` 의 합이다.
이미 현금성 자산이 접힌(`foldCash`) 뒤의 값이므로 CMA·발행어음이 주식으로 잡히지 않는다.

**카드 표시 규칙 (사양 §8.1):**
- 판정 문구는 세 가지다. HOLD 는 "실행하지 않습니다", CUT 은 "비중을 X%로 줄입니다",
  ADD 는 "비중을 X%로 늘립니다".
- 설명에 실행 방식을 붙인다. CUT 은 "당일 또는 익일에 한 번에", ADD 는 "3영업일 간격으로
  3회에 나누어". HOLD 는 차이가 몇 %p 로 기준에 미달하는지 적는다.
- `signal.window < 756` 이면 "백분위 창 N/756일" 을 붙인다.
- 캐시가 모자라면 판정 대신 "아직 계산할 수 없습니다 — 거래일 N, 최소 약 500일" 을 보여 준다.
- 마지막 계산 후 7일이 지나면 갱신을 권하는 문구를 덧붙인다. 주 1회 신호이므로 그 이전에는
  아무 말도 하지 않는다.
- 최초 백필이면 "약 30 MB 를 내려받습니다. Wi-Fi 를 권합니다" 를 확인 다이얼로그로 띄운다.
  증분 갱신에는 띄우지 않는다.
- 카드를 누르면 밴드 지침 화면으로 간다(Task 5 에서 연결한다).

**Steps:**

- [ ] **Step 1: 실패하는 테스트 작성** — 두 개면 된다. 비례 조정·누적 오차·고아 처리는
      `RebalanceTest` 가 이미 `scaleForCash` 에 대해 보장하므로 다시 쓰지 않는다.
  - `applyMarketTarget(1250)` 뒤 종목 목표 합계가 정확히 1250bp 다 — 인수를 `FULL_BP − bp`
    가 아니라 `bp` 로 잘못 넘기는 실수를 잡는 단 하나의 테스트다.
  - 유지 비중: 예수금 목표가 7500 이면 `heldBp` 는 2500 이고, 예수금 목표가 없으면 실제
    비중이다 — 비교 대상을 실제 비중으로 되돌리는 회귀를 잡는다.
  - 판정 문구가 HOLD/CUT/ADD 에서 각각 기대한 문자열을 만든다(문구 생성은 순수 함수로 뺀다).
- [ ] **Step 2: 테스트 실패 확인**
- [ ] **Step 3: 카드와 뷰모델 작성**
- [ ] **Step 4: 검증** — `./gradlew testDebugUnitTest detekt ktlintCheck --no-daemon`
- [ ] **Step 5: 커밋** — `feat(market): 판정 카드와 모델 목표 적용`

---

## Task 5: 밴드 지침 화면 — `market/BandGuide.kt`

**Files:**
- Create: `app/src/main/kotlin/dev/nhportfolio/market/BandGuide.kt`
- Modify: `app/src/main/kotlin/dev/nhportfolio/MainActivity.kt` (라우트 1개)

**내용은 전부 정적 상수다.** `docs/manage/operating_guidebook.html` 2절의 밴드별 지침과
관측값을 그대로 옮긴다. **앱이 다시 계산하지 않는다** — 이 수치는 2004-01-02 ~ 2026-09-04
백테스트의 산출물이며, 앱이 가진 4년치 데이터로는 재현할 수 없다.

| 밴드 | 비중 | 체류 | 중앙/최장 지속 | 연환산 변동성 | 3개월 −10% | 3개월 +10% | 3개월 최악 |
|---|---|---|---|---|---|---|---|
| 최대 방어 | 0–13% | 8.8% | 44일 / 272일 | 30.7% | 29% | 19% | −42.3% |
| 방어 | 13–44% | 21.0% | 44일 / 273일 | 24.0% | 8% | 11% | −32.8% |
| 중립 | 44–56% | 30.5% | 36일 / 269일 | 17.5% | 7% | 10% | −23.0% |
| 적극 | 56–81% | 16.0% | 44일 / 158일 | 20.1% | 2% | 18% | −25.4% |
| 최대 투입 | 82–100% | 9.2% | 70일 / 213일 | 29.1% | 4% | 44% | −18.9% |

- 밴드마다 "해야 할 일" 과 "하지 말아야 할 일" 을 가이드북 문구 그대로 넣는다.
- "이 신호가 알려 주지 않는 것"(가이드북 3절)과 "절대 하지 않는 다섯 가지"(6절)를 접이식으로 넣는다.
- 화면 아래에 출처를 밝힌다: "2004-01-02 ~ 2026-09-04 코스피 5,597거래일 백테스트.
  CAGR 9.26%, 최대낙폭 −19.89%. 투자자문이 아니다."
- **유니버스가 코스피200 구성종목으로 바뀌었다는 사실도 같은 자리에 적는다.** 위 수치는
  시가총액 상위 250 으로 검증된 값이고 앱의 신호는 그것과 완전히 같지 않다. 숫자를
  보여 주면서 그 조건을 감추면 안 된다.

**Steps:**
- [ ] **Step 1: 지침 데이터와 화면 작성** (순수 데이터 + Compose. 새 테스트는 만들지 않는다 —
      정적 문자열 표에 단위 테스트를 붙여 봐야 상수를 두 번 적는 것뿐이다)
- [ ] **Step 2: 라우트 연결과 카드에서의 이동**
- [ ] **Step 3: 검증** — `./gradlew assembleDebug detekt ktlintCheck --no-daemon`
- [ ] **Step 4: 커밋** — `feat(market): 밴드별 운영 지침 화면`

---

## Task 6: 문서와 스모크 체크리스트

**Files:**
- Modify: `README.md`

**Steps:**

- [ ] **Step 1: 사양·계획 목록에 두 문서를 추가한다**
- [ ] **Step 2: 기기 스모크 체크리스트에 아래 항목을 더한다** (사양 §9 의 미지수를 실기기에서
      답하는 자리다)
  - [ ] 최초 백필이 끝까지 도는가. 걸린 시간과 받은 거래일 수를 기록한다.
        **1,065일에 못 미치면 `array_cnt` 상한이나 조회 가능 기간에 걸린 것이다.** 그 경우
        카드에 "백분위 창 N/756일" 이 붙는지, 약 500일 미만이면 "아직 계산할 수 없습니다"
        가 나오는지 확인한다.
  - [ ] 목표 비중을 적용한 뒤 카드의 유지 비중이 `100% − 예수금 목표` 와 같고, 그 다음 판정이
        "실행하지 않습니다" 인가(모델이 두 단계 움직이기 전까지).
  - [ ] `etfComponents(069500)` 가 몇 종목을 주는가. 200 미만이면 상위 구성만 주는 API 다.
  - [ ] 백필 도중 화면을 떠났다가 돌아오면 이어받는가. 처음부터 다시 받지 않는가.
  - [ ] 비행기 모드에서 갱신 → "네트워크 오류" 이고 기존 캐시로 계산한 신호가 남아 있는가.
  - [ ] 액면분할이 있었던 종목의 보정 결과가 맞는가. 분할 전후 종가가 연속인지 확인한다.
  - [ ] 카드의 현재 주식 비중이 포트폴리오 화면의 종목 비중 합과 일치하는가.
  - [ ] "이 목표로 맞추기" 를 누른 뒤 목표 합계 배지가 100% 이고 매수/매도 수량이 갱신되는가.
  - [ ] 갱신 도중 429 가 나오면 지연만 하고 토큰을 다시 발급하지 않는가
        (프록시로 `/oauth2/token` 호출 수를 센다).
- [ ] **Step 3: 커밋** — `docs: 시장 비중 관리 기능 문서화`

---

## 범위 밖으로 남긴 것

명시적으로 하지 않는다. 필요해지면 별도 계획으로 다룬다.

| 항목 | 이유 |
|---|---|
| 3영업일 3회 분할 실행 추적 | 1차 범위에서 제외하기로 확정했다. 카드가 방식을 문장으로 안내한다 |
| 개별 종목 −8% 손절 배지 | 시장 비중 관리와 독립된 계층이다. 두 줄이면 되지만 이번 기능의 일부는 아니다 |
| 연 1회 폐기 조건 자동 점검 | 코스피 지수 계열과 모델 NAV 가 필요하다. NH 에 지수 API 가 없어 대용가 의존이 커진다 |
| 시장폭·비중 이력 차트 | 1차 범위에 없다. 필요해지면 Compose Canvas 로 그린다 |
| 백그라운드·야간 자동 갱신 | DEK 가 잠금 해제 중에만 있고, 주 1회 신호에 자동화가 주는 이득이 없다 |
| 코스닥·해외 확장 | `etfComponents` 의 ETF 코드만 바꾸면 되는 구조로 두되, 이번에 만들지는 않는다 |
