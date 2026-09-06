# 시장 신호 효용성 추적: 검토 결과·사양·구현 계획 (2026-09-06)

> **상태**: 2026-09-06 브랜치 `feat/market-track` 에서 구현을 완료했으며, §11 의 확인
> 항목은 모두 권장안으로 확정되었다.
>
> **요청 원문**: "추가된 시장 상황에 따른 목표 비중 조절 기능의 효용성을 추적할 수 있도록
> 만들어 주세요. 분석된 데이터를 db에 저장 / 사용자가 원할 때 분석 데이터와 실제 시장 데이터
> 및 상관관계 추이를 차트로 표시 / 분석 결과와 실제 시장 데이터가 잘 맞거나 맞지 않는 원인을
> 분석 / 원인 분석은 분석에 필요한 충분한 데이터가 쌓였을 때만 실행되도록 기본 필요 데이터량을
> 설정 / 기능 제작 전 구현 가능성 검토 / 가능하면 개발 계획 수립 후 확인, 확인 후 구현."
>
> **결론 한 줄**: 구현할 수 있다. 새 의존성 없이(DB 엔진도 차트 라이브러리도 없이) 순수
> 코틀린 계산 파일 하나와 화면 하나로 끝난다. 다만 "실제 시장"은 코스피 지수가 아니라
> KODEX 200 종가(대용)와 유니버스 동일가중 평균이고, 관측 기록은 사용자가 갱신을 누를 때
> (주 1회)마다 한 줄씩 쌓이므로, 관측 기록만으로 원인 분석이 가능해지는 시점은 기록 시작
> 후 약 9개월이다. 그 전에는 캐시로 소급 계산한 시계열을 같은 화면에 "소급" 라벨로 함께
> 보여 준다.
>
> **근거 자료**: `docs/manage/{guide_stats.py,riskmodel.py,operating_guidebook.html}`,
> `docs/superpowers/specs/2026-09-05-market-exposure-model.md`(이하 "사양"),
> `docs/superpowers/specs/2026-09-05-history-timeseries-feasibility.md`(이하 "이전 검토"),
> 현재 `market/` 패키지 소스.

## 1 판정 요약

| 요구사항 | 판정 | 핵심 근거 |
|---|---|---|
| 분석 데이터를 DB 에 저장 | 가능하다 | 기존 `filesDir/market/` JSON 캐시와 같은 방식으로 `track.json` 한 파일에 쌓는다. 행 하나가 약 120바이트, 연 250행 이하라 연 30 KB 다. DB 엔진이 주는 이점이 없다(이전 검토 §3.4 와 같은 결론). SQLite 를 원하면 프레임워크 `SQLiteOpenHelper` 로 의존성 없이 가능하다 |
| 실제 시장 데이터 | 조건부 가능하다 | NH 에 지수 API 가 없다(사양 §3.2). KODEX 200(069500) 일봉을 `dailyBars` 로 한 종목 더 받으면 시가총액가중 대용이 되고, 이미 캐시된 199종목 종가의 동일가중 평균은 통신 없이 얻는다. 069500 이 `period` 에 응답하는지는 실기기 확인이 필요하다(§8) |
| 분석·시장·상관관계 차트 | 가능하다 | Compose `Canvas` 로 선 몇 개와 축을 그린다. 차트 라이브러리는 들이지 않는다(사양 §10 의 결정 유지) |
| 잘 맞음/맞지 않음의 원인 분석 | 가능하다(규칙 기반) | 원인 후보를 정해진 목록(좁은 장세·지연·부분 창·경계 잡음·15%p 억제·미적용·유니버스 교체·오래된 데이터·표본 부족)으로 두고 각각을 데이터로 계량한다(§3). 자유 서술형(LLM) 분석은 시장·비중 데이터를 외부로 보내야 해 위협 모델과 맞지 않고 결과가 비결정적이라 채택하지 않는다 |
| 충분한 데이터가 쌓였을 때만 원인 분석 | 가능하다 | 성숙 관측 24개 이상, 첫 관측과 마지막 관측 사이 126거래일 이상, 밴드 2종 이상(§5). 코드 상수로 두고 값의 근거를 적는다 |
| 신호 이력 소급 | 부분적으로 가능하다 | 캐시가 1,100거래일이라 완전 창(756) 신호는 최근 약 35일, 부분 창(252~755) 신호는 약 600일까지 소급된다. 오늘의 유니버스로 계산되므로(사양 §4) 관측 기록과 구분해 표시한다 |

## 2 "잘 맞는다"의 정의: 무엇을 무엇과 비교하는가

이 절이 이 기능에서 가장 중요하다. 정의를 잘못 잡으면 옳게 동작하는 모델을 "틀렸다"고
판정하게 된다.

가이드북 3절은 이렇게 적는다. "시장폭 백분위는 지수가 오를지 내릴지를 예측하지 않는다.
최대 방어 밴드에서도 3개월 뒤 지수는 중앙값 +3.1%로 올랐고 상승 확률은 59%였다. 이
지표가 실제로 구분하는 것은 결과가 흩어지는 정도다." 따라서 **"낮은 밴드인데 지수가
올랐다"는 불일치가 아니다.** 모델이 주장하는 것은 두 가지이고, 효용성은 그 두 주장으로
측정한다. 사용자가 직관적으로 기대하는 방향 적중률은 보여 주되 모델의 주장이 아니라고
표기한다.

| 구분 | 측정 | 비교 대상 | "맞음"의 기준 |
|---|---|---|---|
| **A. 위험 분리** (모델의 주장) | 관측 시점의 밴드별로 이후 63거래일의 실현 변동성(연환산), 지수가 10% 넘게 빠진 비율, 10% 넘게 오른 비율, 최악값, 중앙값 | `BandGuide.kt` 의 검증값(2004~2026 백테스트 산출물) | 표본이 5개 이상인 밴드 중 가장 낮은 밴드의 변동성과 하락 확률이 가장 높은 밴드보다 크다 |
| **B. 전략 가치** | 관측된 목표를 15%p 규칙으로 실행한 모의 NAV(원 `backtest()` 이식, 매도 0.17%·매수 0.02% 비용) 와 단순 보유(069500) 의 수익률·최대낙폭. 가이드북 7절 폐기 조건 네 가지를 자동 점검한다 | 단순 보유, 그리고 폐기 조건의 기준선 | 모의 NAV 의 최대낙폭이 단순 보유보다 작고 폐기 조건에 하나도 걸리지 않는다 |
| **C. 방향 적중** (모델의 주장이 아님) | 적극·최대 투입 밴드 뒤 63거래일 지수 상승, 방어·최대 방어 밴드 뒤 하락을 적중으로 센다. 중립은 제외한다 | 없음 | 기준 없음. "모델은 방향을 주장하지 않는다"는 문구와 함께 비율만 보여 준다 |

B 는 사양 §1 에서 "1차 범위 밖"으로 미뤄 둔 **"폐기 조건 자동 점검(연 1회)"** 을 그대로
채운다. 가이드북 8절이 연 1회 점검을 운영 작업으로 지시하므로, 이 기능이 그 자리를 맡는다.

### 2.1 상관관계 추이

신호의 연속값(양자화 전 앙상블 점수 `score`, §4.1)과 지수 수익률 사이의 롤링 피어슨
상관계수를 126거래일 창으로 두 가지 구한다.

- **동행**: 관측 시점의 직전 63거래일 지수 수익률과의 상관. 신호가 시장을 얼마나
  따라가는지 보여 준다. 시장폭은 가격에서 나오므로 높게 나오는 것이 정상이다.
- **선행**: 관측 시점의 이후 63거래일 지수 수익률과의 상관. 신호가 시장을 앞서는지
  보여 준다. 낮거나 0 근처인 것이 정상이며, 이 두 선의 차이 자체가 "이 신호는 예측이
  아니라 추세 추종"이라는 사실을 그림으로 보여 준다.

창 안에 성숙 관측이 12개 미만이면 그 지점의 상관계수는 비워 둔다(NaN). 이후 63거래일
수익률이 아직 없는 최근 관측은 선행 상관에 들어가지 않으므로 선행 선은 항상 오늘보다
63거래일 앞에서 끝난다.

## 3 원인 목록과 계량 방법

원인은 자유 서술이 아니라 아래 목록에서 데이터로 계량한 항목만 나온다. 각 항목은 값과
함께 "해당함/해당 없음"으로 표시된다.

| 원인 | 계량 | 해당 기준 | 데이터 |
|---|---|---|---|
| 좁은 장세 | 낮은 밴드(방어·최대 방어)인데 이후 63거래일 지수가 오른 관측에서, 지수 수익률에서 동일가중 평균 수익률을 뺀 값의 평균 | +3%p 초과 | 069500 + 캐시 종가 |
| 지연 | 신호 점수의 일별 변화와 지수 일별 수익률의 교차상관이 최대가 되는 시차 k(−60~+60). 양수면 시장이 앞선다 | k ≥ 5 | 소급 시계열(모델의 성질이라 관측 밀도와 무관하게 소급 시계열로 잰다) |
| 부분 창 | 성숙 관측 중 `window < 756` 인 비율 | 50% 초과 | 행의 `window` |
| 경계 잡음 | 목표가 바뀐 연속 관측 쌍 중 어느 한쪽의 `score` 가 가장 가까운 양자화 경계((2k+1)/16)에서 1/32 안에 있는 비율 | 50% 초과 | 행의 `score` |
| 15%p 억제 | 목표가 바뀐 횟수 대비 모의 실행(§2 B 의 `held` 경로)이 실제로 일어난 횟수 | 실행이 변경의 절반 미만 | 행 + 모의 경로 |
| 미적용 | 모의 실행(CUT/ADD)일로부터 5거래일 안에 "이 목표로 맞추기" 기록이 없는 비율 | 1건이라도 | 적용 기록(계좌별, §4.2) |
| 유니버스 교체 | 성숙 관측들의 `universeAt` 이 서로 다른 값의 수 | 2종 이상 | 행의 `universeAt` |
| 오래된 데이터 | `computedOn − asOf > 7일` 인 관측 비율 | 25% 초과 | 행 |
| 표본 부족 | 밴드별 성숙 관측 수가 5 미만인 밴드 | 1개라도 | 행 수 |

"좁은 장세"가 이 목록에서 유일하게 **모델 밖의 시장 구조**를 짚는 항목이다. 시장폭은
종목 수를 세고(동일가중) 지수는 시가총액으로 가중하므로, 소수 대형주가 지수를 끌어올리는
국면에서 둘은 구조적으로 어긋난다. 동일가중 평균은 캐시된 종가에서 통신 없이 나오고,
069500 과의 차이가 곧 이 어긋남의 크기다.

## 4 데이터

### 4.1 관측 기록: `filesDir/market/track.json`

```
{ "rows": [ { "asOf": "20260904", "computedOn": "20260906",
              "targetBp": 2500, "band": "DEFENSE", "score": 0.3125,
              "breadth": 0.415, "pctile": 0.099, "window": 756,
              "universeAt": "20260905", "universeSize": 199 }, … ] }
```

- **언제 쓰는가**: `PortfolioViewModel.reloadMarket()` 이 신호를 계산한 직후
  `MarketData.record(signal, today)` 를 부른다. 화면 진입마다 불리지만 같은 `asOf` 는 한
  행이므로 실제 쓰기는 거래일당 최대 한 번이다.
- **덮어쓰기 규칙**: `asOf` 별로 한 행이다. 없으면 추가한다. 있으면 그 행의 `computedOn`
  이 오늘일 때만 교체한다(같은 날 재계산·부분 실패 뒤 재갱신 복구용). 다른 날에 쓴 행은
  절대 바꾸지 않는다. 이것이 "그날 앱이 사용자에게 보여 준 값"을 지키는 규칙이며, 분기
  유니버스 교체 뒤 같은 `asOf` 를 다시 계산해도 과거 행이 새 유니버스 값으로 바뀌지 않는다.
- **`score`**: `Breadth` 가 9개 구성을 1/8 로 양자화한 뒤 평균한 값(0~1)이다. 최종 목표는
  이 값을 한 번 더 양자화한 것이라 `Signal` 에 새 필드로 노출한다. 상관계수와 경계 잡음
  진단에 쓴다.
- **계좌 정보가 없다.** 유지 비중·판정·적용 여부는 계좌에 따라 다르므로 이 파일에 넣지
  않는다. `filesDir/market/` 에 계좌·보유·목표 비중이 없다는 README 의 정책이 그대로
  유지된다. 판정(HOLD/CUT/ADD)은 저장하지 않고 §2 B 의 모의 실행 경로에서 모델 자신의
  규칙으로 다시 만든다.
- **손상**: 파일이 JSON 으로 읽히지 않으면 빈 기록으로 본다(`MarketData` 의 기존 원칙).
  행 단위 검증(날짜 8자리·bp 범위·score 0~1)을 통과하지 못한 행만 버린다.

### 4.2 적용 기록: 계좌별 DataStore 키 `applied_<계좌해시>`

`[{"date":"20260906","exposureBp":2500}, …]`. "이 목표로 맞추기"를 누를 때
`applyMarketTarget()` 이 한 줄 덧붙인다. 연 몇 회라 DataStore 전체 재작성 비용은 문제가
되지 않고, 목표 비중이 이미 평문 DataStore 에 있으므로 보안 정책도 달라지지 않는다.
`store/Prefs.kt` 의 `accountKey()` 규칙(SHA-256 해시 접두)을 그대로 쓴다.

### 4.3 지수 대용: `filesDir/market/index/069500.json`

`sync()` 가 유니버스 종목 뒤에 069500 을 한 종목 더 받는다(최초 약 5페이지·11초, 이후
주간 1회). `bars/` 가 아니라 `index/` 에 두는 이유는 두 가지다. `loadCalendar()` 는
`universe.codes` 만 읽으므로 어디에 두든 시장폭에 섞이지는 않지만, 파일 위치로도 역할이
갈리는 편이 읽기 쉽고, 유니버스 교체 시 삭제 대상 목록과도 확실히 분리된다. 형식은
`BarsFile` 그대로이고 수정주가 보정도 같은 `Breadth.adjust()` 를 탄다. 069500 의 분배금
락은 순수 배당락(보정 제외 코드)이거나 2% 가드 아래라 보정되지 않으며, 그래서 이 대용
지수는 분배금만큼(연 1~2%) 총수익을 밑돈다. 63거래일 비교에서는 무시할 수준이고 화면
각주에 적는다.

### 4.4 동일가중 평균

캐시 종가에서 날짜별 종목 수익률의 산술평균을 누적한다(첫날 1.0). 상장 전(0)인 종목은 그
날 분모에서 뺀다. 유니버스 교체로 지워진 종목은 빠지므로 생존 편향이 있다(사양 §6.2 와
같은 성질). 화면 각주에 적는다.

### 4.5 소급 시계열

`Breadth.series()` 가 캐시 전체에 대해 날짜별 `Signal?` 을 돌려준다(§10 Task 1). 관측
기록이 비어 있는 첫날부터 차트가 채워지고, 원인 분석도 이 시계열에 대해 돌릴 수 있다.
다만 오늘의 유니버스로 소급 계산한 값이라 "그날 앱이 보여 준 값"이 아니므로 관측 기록과
같은 화면에서 토글로 갈라 보여 주고 "소급(오늘의 유니버스 기준)" 라벨을 붙인다.
저장하지 않고 매번 계산한다(사양 §5.3 의 원칙).

### 4.6 용량과 비용

| 항목 | 값 |
|---|---|
| `track.json` | 연 30 KB 이하 |
| `index/069500.json` | 약 20 KB |
| 소급 시계열 계산 | 기존 `cached()` 와 같은 파이프라인에 롤링 평균 9개가 더해진다. 100 ms 안팎 |
| 성숙 관측당 통계 | 63거래일 창 하나. 관측 600개여도 수 ms |
| 통신 | 069500 한 종목분. 최초 약 300 KB, 이후 주간 수 KB |

## 5 데이터 충분 기준(게이트)

원인 분석(§3)과 판정 A·B 는 아래 세 조건을 모두 채울 때만 계산한다. 차트에는 게이트를
걸지 않는다. 관측이 하나뿐이어도 그 하나를 그린다.

| 상수 | 값 | 근거 |
|---|---|---|
| `HORIZON` | 63거래일 | 가이드북 밴드 통계(3개월)와 같은 창. 관측은 이후 63거래일 종가가 있어야 "성숙"이다 |
| `MIN_MATURED` | 24 | 피어슨 상관계수의 표준오차가 약 1/√n 이라 n=24 에서 약 0.2 다. 그 아래에서는 |r| 0.4 미만을 0 과 구분할 수 없다 |
| `MIN_SPAN` | 126거래일 | 밴드 중앙 지속 기간(36~70일)의 두세 배는 지나야 밴드가 한 번 이상 바뀐 기록이 된다 |
| `MIN_BANDS` | 2 | 밴드가 하나뿐이면 위험 분리(§2 A)를 검정할 수 없다 |
| `MIN_PER_BAND` | 5 | 밴드별 통계 표시 하한. 미만이면 그 밴드는 "표본 부족" |
| `CORR_WIN` / `CORR_MIN_OBS` | 126거래일 / 12 | 롤링 상관 창과 창 안 최소 관측 |

관측 기록 기준으로 주 1회 갱신하면 약 6개월(24행)에 3개월 성숙이 더해져 **기록 시작 후
약 9개월**에 게이트가 열린다. 소급 시계열은 즉시 통과한다. 게이트가 닫혀 있으면 화면에
"원인 분석까지: 성숙 관측 N/24 · 기간 D/126일 · 밴드 B/2" 를 보여 준다.

상수는 `market/Track.kt` 의 `const val` 로 둔다. 설정 화면에서 조정하는 기능은 만들지
않는다(§9).

## 6 화면

- **진입**: 시장 신호 카드의 "갱신" 옆에 "효용성" 텍스트 버튼. 경로는 `Route.Track(acctNo)`
  (적용 기록이 계좌별이라 계좌번호가 필요하다).
- **상단 토글**: "관측 기록" / "소급 계산". 아래 모든 내용이 토글에 따라 바뀐다.
- **차트 1 (시장과 신호)**: 069500(첫날 100 정규화), 동일가중 평균(첫날 100), 모델 목표
  (계단선, 오른쪽 축 0~100%). 관측 기록 모드에서는 관측 행을 점으로 찍고 그 사이는 마지막
  값을 유지하는 계단으로 잇는다. 마지막 63거래일은 "아직 성숙하지 않음" 음영.
- **차트 2 (상관관계 추이)**: 동행·선행 롤링 상관 두 선, −1~+1 축, 0 기준선.
- **판정 카드**: A·B·C 한 줄씩. 예: "위험 분리: 맞음(최대 방어 변동성 28% > 적극 17%)",
  "전략 가치: 최대낙폭 −9% vs 단순 보유 −16%, 폐기 조건 0/4", "방향 적중 54% (모델의
  주장이 아님)".
- **밴드별 표**: 실측(n, 변동성, −10% 확률, +10% 확률, 최악) 과 검증값을 나란히. n < 5 는
  "표본 부족".
- **폐기 조건 점검**: 네 줄. 각각 해당/미해당/판정 불가(252거래일이 안 쌓임).
- **원인 분석**: 게이트 통과 시 §3 목록. 해당 항목을 위에, 해당 없음은 접어서 아래에.
  게이트 미통과 시 진행 표시 한 줄.
- **각주**: 검증값 출처(`BandGuide` 의 `FOOTER_1`·`FOOTER_2` 재사용), 069500 분배금 미반영,
  동일가중 평균의 생존 편향, 소급 계산의 유니버스 기준.
- 터치 스크럽(손가락으로 값 읽기)은 1차 범위 밖이다. 마지막 값만 범례에 적는다.
- 폴더블·큰 글꼴: 차트는 너비를 채우고 높이는 dp 고정. 축 라벨은 `bodySmall`.

## 7 앱 구조에 붙는 방식

기존 경계를 바꾸지 않는다. `NhApi` 는 여전히 NH 를 아는 유일한 파일이고(메서드 추가
없음: `dailyBars` 를 그대로 쓴다), 새 계산은 네트워크도 안드로이드도 모르는 순수 함수이며,
화면은 파일 형식을 모른다.

| 파일 | 책임 | Task |
|---|---|---|
| `market/Breadth.kt` | `series()` 추가, `Signal.score` 추가. `signal()` 은 `series().lastOrNull()` 이 된다 | 1 |
| `market/Track.kt` (신규, 순수) | 관측·시장 정렬, 성숙 판정, 판정 A·B·C, 모의 NAV, 폐기 조건, 롤링 상관, 교차상관 시차, 게이트, 원인 목록 → `Report` | 2 |
| `market/MarketData.kt` | `sync()` 에 069500, `record()`, `trackData()`(캐시를 한 번 읽어 화면에 필요한 모든 것을 돌려줌) | 3 |
| `store/Prefs.kt` | `appliedKey()`, `readApplied()` | 4 |
| `portfolio/PortfolioScreen.kt` | `reloadMarket()` 뒤 `record()`, `applyMarketTarget()` 에서 적용 기록, 카드 버튼 | 4 |
| `market/TrackScreen.kt` (신규) | `TrackViewModel`, Canvas 차트, 카드 | 5 |
| `MainActivity.kt`, `App.kt` | `Route.Track`, Koin `viewModel` 등록 | 5 |
| `config/detekt.yml` | `ForbiddenImport` 대상에 `**/market/Track.kt` 추가 | 2 |
| `README.md`, 이 문서 | 기능 설명, 스모크 항목, 상태 갱신 | 6 |

`Track.kt` 를 `Breadth.kt` 와 같은 격리 규칙(라이브러리 import 금지, 은행가 반올림만)에
둔다. 여기서 나오는 숫자로 사용자가 "전략을 폐기할지"를 결정하므로 `Breadth` 와 같은
등급으로 다룬다.

## 8 실기기로만 확인할 수 있는 미지수

1. **069500 이 `quote/v1/period`(`market_cd=UNT`) 에 응답하는가.** ETF 도 거래소 종목이라
   응답할 가능성이 높지만 확인된 바 없다. 응답하지 않으면 동일가중 평균만으로 동작한다.
   차트·판정·원인 분석 모두 가능하고 "좁은 장세" 진단 하나만 빠진다. 구현은 `index` 가
   null 일 수 있도록 처음부터 만든다.
2. **069500 분배금 락일의 `flng_cls_code`.** 보정 대상 코드(01·04·06·07)가 아니어야 한다.
   설령 그 코드로 와도 분배금은 2% 가드 아래라 실질 영향이 없다.
3. **관측 기록이 실제로 주 1회 쌓이는가.** 사용자가 갱신을 눌러야 행이 생긴다. 앱 정책상
   자동 갱신은 없으므로 이것은 확인 사항이지 구현 사항이 아니다.

## 9 검토했으나 채택하지 않은 것 (기록)

| 선택지 | 채택하지 않은 이유 |
|---|---|
| Room 또는 SQLite 도입 | 연 30 KB 의 추가 전용 기록에 DB 엔진의 이점이 없다. 이전 검토 §3.4 와 같은 결론. 요구가 SQLite 자체라면 프레임워크 `SQLiteOpenHelper` 로 의존성 없이 가능하다(§11 확인 항목) |
| 차트 라이브러리 | 선 다섯 개와 축이면 `Canvas` 로 충분하다(사양 §10 의 결정) |
| LLM 서술형 원인 분석 | 시장·비중 데이터를 외부 서비스로 보내야 하고, 결과가 실행마다 달라진다. 위협 모델("신뢰 경계는 NH 서버까지")과 맞지 않는다 |
| 실제 계좌 수익률로 효용성 측정 | 종목 선택 효과와 비중 조절 효과를 분리할 수 없고, 과거 예수금은 복원되지 않는다(이전 검토 §3.2). 모의 NAV(§2 B)로 대신한다 |
| 방향 적중률을 "맞음"의 기준으로 | 모델이 주장하지 않는 것으로 모델을 채점하게 된다(가이드북 3절). 참고값으로만 보여 준다 |
| 소급 시계열을 관측 기록에 섞어 저장 | 오늘의 유니버스로 계산한 값이라 그날 보여 준 값이 아니다. 저장하면 point-in-time 기록의 의미가 사라진다. 매번 계산하고 라벨을 붙인다 |
| 유지 비중·판정을 관측 행에 저장 | 계좌별 값이라 `filesDir/market/` 정책과 충돌한다. 판정은 모델 자신의 규칙(모의 경로)으로 재구성한다 |
| 게이트를 설정 화면에서 조정 | 상수 하나다. 필요해지면 그때 설정 항목으로 승격한다 |
| 코스피 지수 원계열 | NH 에 없다(사양 §3.2) |
| 터치 스크럽·확대 | 1차 범위 밖 |
| 백그라운드 자동 기록 | 앱 정책(포그라운드·사용자 트리거 전용)과 충돌한다 |

## 10 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development.
> 작업은 태스크 단위로 Worker(Sonnet)에게 위임하고, 태스크마다 하위 Advisor(Opus)가
> 리뷰한 뒤 최상위 Advisor 가 검증 명령을 직접 실행해 완료를 확인한다. 브리프에는 이
> 문서의 해당 절과 아래 함정을 그대로 싣는다.

**Goal:** 시장 신호가 계산될 때마다 관측 행을 남기고, 사용자가 원할 때 관측(또는 소급)
시계열과 시장 대용 지수·상관관계 추이를 차트로 보이며, 데이터가 충분히 쌓였을 때만
규칙 기반 원인 분석을 돌린다.

**Branch:** `feat/market-track`. 태스크마다 커밋한다. 원격 푸시는 하지 않는다. 메시지
끝에 다음 두 줄을 붙인다.

```
Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01WxcKt3FgEABem3fvr5SHqA
```

**Global Constraints:** `2026-09-05-market-exposure.md` 의 Global Constraints 를 그대로
승계한다. 특히 다음을 지킨다.

- `market/Breadth.kt`·`market/Track.kt` 는 라이브러리 import 0개, 반올림은
  `kotlin.math.round` 만 쓴다. `roundToInt()`·`Math.round()` 가 보이면 반려한다.
- 새 로그를 하나도 추가하지 않는다.
- 비중은 bp `Int`, 통계 중간값만 `Double`. 화면에 나가는 비율은 `bpPct()`/`pct()` 로 만든다.
- 모르면 지어내지 않는다. 표본이 모자라면 숫자 대신 "표본 부족"·"판정 불가"를 낸다.
- 검증 명령을 실제로 실행하고 출력을 확인한 뒤에만 완료로 표시한다.

```bash
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew ktlintFormat ktlintCheck detekt testDebugUnitTest
```

### Task 1: `Breadth.series()` 와 `Signal.score`

**Files:** `market/Breadth.kt`, `test/BreadthTest.kt`

**Interfaces:**
```kotlin
data class Signal(
    val targetBp: Int, val band: Band, val breadth: Double, val pctile: Double,
    val window: Int, val asOf: String,
    /** 9개 구성을 1/8 로 양자화한 뒤 평균한 값(0~1). 최종 목표는 이를 한 번 더 양자화한 것이다. */
    val score: Double,
)
object Breadth {
    /** [dates] 와 길이가 같은 날짜별 신호. 그 날 정의되지 않으면 null. */
    fun series(closes: Map<String, IntArray>, dates: List<String>): List<Signal?>
    fun signal(closes: Map<String, IntArray>, dates: List<String>): Signal? = series(closes, dates).lastOrNull()
}
```

**Algorithm:** `breadthSeries`·`pctRank` 는 이미 전체 시계열이다. `smoothLast` 를 롤링
평균 시계열(창 안에 NaN 이 하나라도 있으면 NaN)로 바꾸고, 날짜 t 마다 9개 평활값이 전부
정의되면 `roundToEighth` 평균(`score`)과 `quantize(score)` 를 낸다. `window(t)` 는 세
이동평균 길이별로 t 에서 끝나는 756 창 안의 정의된 관측 수의 최솟값이다(롤링 카운트로
O(n)). `Signal.breadth`·`pctile` 은 MA200 의 t 시점 값이다.

**함정:** (1) 기존 `signal()` 의 결과가 한 비트도 달라지면 안 된다. 마지막 원소가 옛
구현과 같음을 랜덤 유니버스로 검증한다. (2) `score` 는 평활값의 평균이 아니라 **양자화한
평활값의 평균**이다(사양 §2 의 5, 두 번 양자화). (3) `Signal` 에 필드가 늘면
`MarketTargetTest` 의 `signal()` 헬퍼에도 `score` 를 넣어야 컴파일된다.

**Tests:** 랜덤 유니버스에서 `series().last() == 옛 signal()`; 워밍업 구간은 null 이고
그 뒤로는 연속으로 정의됨; `score` 가 `[0, 1]` 이고 `quantize(score) == targetBp`;
`window` 가 창이 찰 때까지 단조 증가해 756 에서 멈춤; 5개 0.19 + 4개 0.17 예시에서
`score == 0.1875 × …` 처럼 두 번 양자화가 드러나는 벡터 하나.

### Task 2: 순수 평가 `market/Track.kt`

**Files:** `market/Track.kt`(신규), `test/TrackTest.kt`(신규), `config/detekt.yml`

**Interfaces:**
```kotlin
/** 관측 한 건. track.json 의 행을 옮긴 시장 무관 타입이다. */
data class Obs(val asOf: String, val computedOn: String, val targetBp: Int, val score: Double,
               val window: Int, val universeAt: String)
/** 달력에 정렬된 시장 시계열. [index] 는 069500 정규화 종가(없으면 null), [equal] 은 동일가중 누적(첫날 1.0). */
data class Market(val dates: List<String>, val index: DoubleArray?, val equal: DoubleArray)
data class Applied(val date: String, val exposureBp: Int)

data class Gate(val matured: Int, val spanDays: Int, val bands: Int) { val ok: Boolean }
data class BandStats(val band: Band, val n: Int, val vol: Double, val drop10: Double,
                     val rise10: Double, val worst: Double, val median: Double)
enum class Check { OK, TRIPPED, UNKNOWN }
data class Tripwire(val name: String, val check: Check, val detail: String)
data class Strategy(val ret: Double, val mdd: Double, val holdRet: Double, val holdMdd: Double,
                    val trades: Int, val tripwires: List<Tripwire>)
enum class Cause { NARROW, LAG, PARTIAL, BOUNDARY, SUPPRESSED, NOT_APPLIED, UNIVERSE, STALE, SAMPLE }
data class Diagnosis(val cause: Cause, val flagged: Boolean, val text: String)
enum class Fit { MATCH, MISMATCH, UNKNOWN }
data class Report(
    val gate: Gate, val riskFit: Fit, val strategyFit: Fit, val direction: Double?,
    val bands: List<BandStats>, val strategy: Strategy?,
    val coincident: DoubleArray, val predictive: DoubleArray,   // dates 정렬, 비면 NaN
    val diagnoses: List<Diagnosis>,                              // gate.ok 일 때만
)

object Track {
    const val HORIZON = 63; const val MIN_MATURED = 24; const val MIN_SPAN = 126
    const val MIN_BANDS = 2; const val MIN_PER_BAND = 5; const val CORR_WIN = 126; const val CORR_MIN_OBS = 12
    fun equalWeight(closes: Map<String, IntArray>, days: Int): DoubleArray
    fun report(obs: List<Obs>, market: Market, applied: List<Applied>, retroScore: DoubleArray?): Report
    internal fun simulate(targetBp: IntArray /* 날짜별, -1 = 미정 */, prices: DoubleArray): Sim  // backtest() 이식
    internal fun rollingCorr(x: DoubleArray, y: DoubleArray, dates: List<String>): DoubleArray
    internal fun lagPeak(dx: DoubleArray, r: DoubleArray, maxLag: Int = 60): Int
}
```

**Algorithm:**
- 관측을 `market.dates` 위치로 매핑한다. `asOf` 가 달력에 없으면 그 관측은 버린다.
  성숙 = `pos + HORIZON < dates.size`. 시장 시계열은 `index ?: equal`.
- 밴드별 통계: 성숙 관측의 이후 63일 누적 수익률(중앙값·최악·±10% 비율)과 일별 수익률
  표준편차 × √252. `n < MIN_PER_BAND` 면 표시용 행만 남기고 판정에서 뺀다.
- `riskFit`: 표본 5 이상인 밴드가 2종 미만이면 UNKNOWN. 아니면 가장 낮은 밴드의
  `vol`·`drop10` 이 가장 높은 밴드보다 크면 MATCH, 아니면 MISMATCH.
- `simulate`: `riskmodel.backtest()` 의 이식. 전날 종가에 정한 목표를 오늘 수익률에
  적용하고 `|want − held| ≥ 0.15` 일 때만 비용(매도 0.0017·매수 0.0002)을 물고 갈아탄다.
  목표가 미정(-1)인 날은 마지막 목표를 유지한다(관측 기록의 주간 간격을 이렇게 메운다).
  첫 관측 이전은 시뮬레이션에 넣지 않는다.
- 폐기 조건(가이드북 7절): 메커니즘 고장(지수 12개월 −20% 이하인 날에 모의 NAV 12개월
  수익률이 지수를 밑돎), 최대낙폭 −30% 초과, 12개월 수익률 −20% 미만, 직전 252거래일
  실행 15회 초과. 252거래일이 안 쌓였으면 앞 세 개는 UNKNOWN.
- `strategyFit`: `strategy == null` 이면 UNKNOWN. 모의 MDD 가 단순 보유 MDD 보다 작고
  TRIPPED 가 없으면 MATCH.
- `direction`: NEUTRAL 을 뺀 성숙 관측의 적중 비율. 표본 0 이면 null.
- 롤링 상관: 날짜 t 마다 `[t − CORR_WIN + 1, t]` 안의 성숙 관측 (score, 수익률) 쌍으로
  피어슨 상관. 쌍이 `CORR_MIN_OBS` 미만이거나 분산 0 이면 NaN.
- `lagPeak`: `retroScore` 의 일별 차분과 시장 일별 수익률의 교차상관을 −60~+60 시차에서
  구해 최대인 시차. `retroScore` 가 null 이면 LAG 진단은 "판정 불가".
- 게이트 통과 시에만 §3 의 아홉 항목을 채운다. 문구는 값을 포함한 한 문장이다.

**함정:** (1) 은행가 반올림·`round` 만. (2) 표준편차는 표본(n−1). (3) 수익률은 `Double`
이지만 화면에 나갈 비율은 호출부가 `pct()` 로 만든다. (4) 상관계수 창은 관측 개수가
아니라 **거래일** 기준이다(관측 밀도가 주간·일간으로 다르므로). (5) NaN 은 "비어 있음"
이고 절대 0 으로 채우지 않는다.

**Tests (합성 벡터):** `simulate` 를 3일짜리 손 계산과 대조(비용 포함); 15%p 미만 변화는
실행되지 않음; 낮은 밴드 뒤에 변동성이 큰 구간을 붙인 합성 시장에서 `riskFit == MATCH`,
뒤집으면 MISMATCH; 게이트 경계(23/24, 125/126, 1/2 밴드); `rollingCorr` 가 y = x 에서 +1,
y = −x 에서 −1, 관측 11개 창에서 NaN; `lagPeak` 가 5일 밀린 계열에서 5 를 돌려줌;
`equalWeight` 가 상장 전 0 을 분모에서 뺌; 폐기 조건 각각을 만드는 벡터 하나씩; 252일
미만이면 UNKNOWN.

### Task 3: `MarketData` 의 지수 동기화·관측 기록·`trackData()`

**Files:** `market/MarketData.kt`, `test/MarketDataTest.kt`

**Interfaces:**
```kotlin
/** 화면이 필요로 하는 모든 것. 캐시를 한 번만 읽는다. */
data class TrackData(val market: Market, val retro: List<Signal?>, val obs: List<Obs>)
class MarketData {
    fun record(signal: Signal, today: String)   // §4.1 의 덮어쓰기 규칙
    fun trackData(): TrackData
}
```

**Algorithm:** `sync()` 의 종목 목록 뒤에 `INDEX_CODE = "069500"` 을 붙이고 파일 경로만
`index/069500.json` 으로 가른다. 진행 표시의 `total` 은 `codes.size + 1`. 실패는 다른
종목처럼 `failed` 에 센다. `record()` 는 `track.json` 을 읽어 `asOf` 행을 찾고 §4.1 규칙대로
추가·교체한 뒤 기존 `writeJson`(임시 파일 + rename)으로 쓴다. `trackData()` 는
`loadCalendar()` 한 번으로 달력·종가를 얻어 `Breadth.series()`, `Track.equalWeight()`,
지수 파일 정렬(`alignedCloses` 재사용, 첫날 기준 정규화)을 한 번에 만든다.

**함정:** (1) 069500 은 절대 `universe.codes` 에 들어가면 안 된다. (2) `record()` 의 "오늘"
은 화면이 넘긴다(시계를 보지 않는다). (3) 손상된 `track.json` 은 빈 기록. 행 단위 검증을
통과하지 못한 행만 버린다. (4) 유니버스 교체로 지워지는 파일 목록에 `index/` 는 없다.

**Tests (MockEngine, 기존 헬퍼 재사용):** 동기화가 069500 을 `index/` 에 쓰고 시장폭 종목
수에는 안 들어감; `record` 첫 쓰기·같은 날 교체·다른 날 보존·손상 파일 복구; `trackData()`
가 지수 없이도(파일 없음) `index == null` 로 동작; `retro` 길이가 달력과 같음.

### Task 4: 저장 키·뷰모델 연결·카드 버튼

**Files:** `store/Prefs.kt`, `portfolio/PortfolioScreen.kt`, `market/MarketCard.kt`,
`test/PrefsTest.kt`, `test/MarketTargetTest.kt`

**Interfaces:**
```kotlin
fun appliedKey(acctNo: String): Preferences.Key<String>
fun readApplied(prefs: Preferences, key: Preferences.Key<String>): List<Applied>
```

**Algorithm:** `reloadMarket()` 이 신호를 얻으면 `market.record(signal, today)` 를 같은
IO 컨텍스트에서 부른다. `applyMarketTarget()` 은 같은 `store.edit` 안에서 적용 기록에
`{오늘, exposureBp}` 를 덧붙인다. `MarketCard` 에 "효용성" 버튼과 `onTrack: () -> Unit`
을 더한다.

**함정:** `record()` 실패(디스크 오류)가 화면을 죽이면 안 된다. `runCatching` 으로 감싸고
오류를 삼킨다(기록은 부가 기능이다).

**Tests:** `readApplied` 가 깨진 값에서 빈 목록; `appliedKey` 가 계좌번호를 키 이름에
남기지 않음; 적용 기록 추가가 순수 함수(`withApplied(list, date, bp)`)로 분리되어 검증됨.

### Task 5: `TrackScreen`·차트·경로

**Files:** `market/TrackScreen.kt`(신규), `MainActivity.kt`, `App.kt`,
`test/TrackTextTest.kt`(문구 헬퍼만)

**Algorithm:** `TrackViewModel(acctNo, market, store)` 가 IO 에서 `market.trackData()` 와
적용 기록을 읽고, 토글 상태에 따라 `Track.report(obs 또는 retro→Obs 변환, …)` 를 만든다.
화면은 §6 의 순서대로 그린다. 차트는 `Canvas` + `drawPath`, 축 라벨은
`rememberTextMeasurer`. 색은 `MaterialTheme.colorScheme` 에서 고른다(다크 테마 대응).
판정·게이트·진단 문구는 `internal fun` 으로 분리해 단위 테스트한다.

**함정:** (1) 화면은 `Track` 의 데이터 클래스만 안다. 파일 형식·NH 필드명을 모른다.
(2) 소급 모드에서 `Signal?` → `Obs` 변환 시 `computedOn = asOf`, `universeAt` = 현재
유니버스 일자로 채운다(그래서 소급 모드에서는 STALE·UNIVERSE 진단이 항상 "해당 없음"이며
문구에 그 이유를 적는다). (3) Compose UI 테스트는 없다(프로젝트 결정). 에뮬레이터에서
직접 확인하고 README 스모크 항목에 남긴다.

### Task 6: 문서

**Files:** `README.md`, 이 문서(상태 갱신)

README 에 "효용성 추적" 절(무엇을 저장하고, 무엇과 비교하며, 게이트가 언제 열리는지),
`filesDir/market/` 설명에 `track.json`·`index/` 추가(계좌 정보 없음 유지), 스모크
체크리스트에 §8 의 세 항목과 화면 확인 항목 추가.

## 11 착수 전 확인 항목

아래는 검토자가 권장안을 정해 둔 결정 사항이다. 다른 선택을 원하면 그 항목만 알려 주면 된다.

1. **저장 형식**: `track.json`(권장) / 프레임워크 SQLite(`SQLiteOpenHelper`, 의존성 없음).
2. **소급 시계열**: 관측 기록과 같은 화면에서 토글로 보여 주고 원인 분석도 허용(권장,
   "소급" 라벨) / 관측 기록만.
3. **게이트 기본값**: 성숙 관측 24 · 기간 126거래일 · 밴드 2종, 코드 상수(권장) /
   다른 값 / 설정 화면 항목.
4. **적용 기록**: "이 목표로 맞추기" 시각을 계좌별 DataStore 에 남겨 "미적용" 진단에
   쓴다(권장) / 남기지 않는다.
5. **실제 시장의 정의**: 069500 종가(시가총액가중 대용) + 유니버스 동일가중 평균(권장).
   코스피 지수 원계열은 NH 에 없어 불가능하다.
