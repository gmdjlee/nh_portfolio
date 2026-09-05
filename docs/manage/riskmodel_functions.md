# `_pct_rank` 와 `score_to_exposure`

`riskmodel.py` 에 정의된 두 함수를 정리한 문서입니다. 시장폭 익스포저 모델에서
원자료를 목표 비중으로 바꾸는 마지막 두 단계를 담당합니다.

```
시장폭 원자료  ──_pct_rank──▶  3년 백분위  ──score_to_exposure──▶  목표 비중
   41.5%                          9.9%                            12.5%
```

---

## 정의

```python
PCT_WIN = 756          # 3 years of trading days for percentile ranks


def _pct_rank(s, win=PCT_WIN):
    """Trailing percentile of the latest value within its own window."""
    return s.rolling(win, min_periods=252).rank(pct=True)


def score_to_exposure(score, floor=0.25, cap=1.0, steps=8):
    """Quantise the composite score into discrete exposure levels."""
    e = floor + (cap - floor) * score
    return np.round(e * steps) / steps
```

---

## `_pct_rank`

`Rolling.rank()` 는 창 안에서 **가장 최근 값**의 순위를 반환하고, `pct=True` 를 주면
그 순위를 창 크기로 나눕니다. 따라서 오늘 시장폭이 최근 756거래일 분포에서
아래에서 몇 번째 자리에 있는지를 0과 1 사이 값으로 돌려줍니다.

절대 수준이 아니라 상대 위치를 쓰는 이유는, 유니버스 구성이나 시장 구조가
달라져도 같은 기준으로 판단하기 위해서입니다.

### 인자

| 인자 | 기본값 | 설명 |
|---|---|---|
| `s` | 필수 | 백분위로 바꿀 시계열 (`pd.Series`) |
| `win` | `756` | 비교 대상 창 길이. 거래일 기준 약 3년입니다. |

### 구현 시 주의할 점

- **값의 범위가 0부터 시작하지 않습니다.** 순위가 1위(최솟값)여도 결과는
  `1/756 ≈ 0.0013` 이며 0이 되지 않습니다. 최댓값은 정확히 `1.0` 입니다.
- **앞 251거래일은 `NaN` 입니다.** `min_periods=252` 이므로 최소 1년치가 쌓여야
  값이 나오고, 252일에서 756일 사이에는 창이 다 차지 않은 상태로 계산됩니다.
  백테스트에서 초기 구간을 집계에서 제외하는 이유가 여기에 있습니다.
- **동점은 평균 순위로 처리됩니다.** pandas 기본값인 `method='average'` 가
  적용됩니다. 시장폭은 250개 종목의 비율이므로 같은 값이 반복될 수 있습니다.
- **`min_periods` 는 `win` 보다 클 수 없습니다.** 짧은 창으로 시험하실 때
  `ValueError` 가 발생하므로 `min_periods` 도 함께 줄이셔야 합니다.

### 동작 예시

```python
s = pd.Series([10, 20, 30, 40, 50, 5])
s.rolling(5, min_periods=1).rank(pct=True)
# [1.0, 1.0, 1.0, 1.0, 1.0, 0.2]
```

마지막 값 `5` 는 직전 5개 값 `[20,30,40,50,5]` 중 1위이므로 `1/5 = 0.2` 입니다.

---

## `score_to_exposure`

점수를 `floor` 와 `cap` 사이로 선형 변환한 뒤 `1/steps` 단위로 반올림합니다.
계단식으로 끊는 이유는 점수가 조금만 움직여도 매매가 발생하는 상황을 막기
위해서입니다.

### 인자

| 인자 | 기본값 | 설명 |
|---|---|---|
| `score` | 필수 | 0과 1 사이의 위험선호 점수 |
| `floor` | `0.25` | 주식 비중 하한 |
| `cap` | `1.0` | 주식 비중 상한 |
| `steps` | `8` | 양자화 단계 수. 8이면 12.5%p 단위가 됩니다. |

### 최종 모델에서는 `floor=0.0` 을 씁니다

기본값은 하한 25%이지만, 최종 앙상블은 `floor=0.0` 으로 호출합니다. 검증
과정에서 하한을 25%로 둔 구성보다 0%가 더 나은 결과를 냈기 때문입니다.
이 경우 계산은 `round(score × 8) / 8` 로 단순해지고, 결과는 다음 아홉 개 값 중
하나가 됩니다.

```
0%   12.5%   25%   37.5%   50%   62.5%   75%   87.5%   100%
```

---

## 다른 언어로 옮기실 때 걸리는 지점

`np.round` 는 **은행가 반올림**(round-half-to-even)을 사용하므로 정확히 0.5인
값을 짝수 쪽으로 보냅니다. 파이썬 내장 `round()` 도 같은 규칙을 따릅니다.

| `score` | `score × 8` | NumPy · Python | 엑셀 `ROUND` · Kotlin `roundToInt` |
|---|---|---|---|
| 0.0624 | 0.499 | 0.000 | 0.000 |
| **0.0625** | **0.500** | **0.000** | **0.125** |
| 0.0626 | 0.501 | 0.125 | 0.125 |
| 0.1875 | 1.500 | 0.250 | 0.250 |
| **0.3125** | **2.500** | **0.250** | **0.375** |

엑셀 `ROUND()` 와 Kotlin `roundToInt()` 는 0.5를 항상 올리므로 경계값에서 한
단계씩 어긋납니다. Kotlin 으로 이식하실 경우 `Math.rint()` 를 쓰시면 NumPy 와
같은 결과가 나옵니다.

```kotlin
// NumPy 와 동일한 동작
val exposure = Math.rint(score * 8.0) / 8.0

// 결과가 달라지는 구현
val exposure = (score * 8.0).roundToInt() / 8.0
```

이 차이 때문에 운영 가이드북의 구현 검증값이 맞지 않을 수 있으므로, 이식
후에는 최근 며칠치 목표 비중을 파이썬 결과와 대조해 보시기 바랍니다.

---

## 앙상블에서의 실제 호출

양자화는 두 번 일어납니다. 9개 구성 각각에서 한 번, 그 평균을 낸 뒤 한 번 더
적용합니다.

```python
ex = [
    score_to_exposure(
        _pct_rank(breadth_raw(st, ma)).rolling(sm).mean().bfill().fillna(.5),
        floor=0.0,
    )
    for ma in (150, 200, 250)
    for sm in (20, 40, 60)
]
ENS = np.round(pd.concat(ex, axis=1).mean(axis=1) * 8) / 8
```

`bfill().fillna(.5)` 는 백분위가 아직 계산되지 않은 초기 구간을 중립값 0.5로
채우는 처리입니다. 해당 구간은 성과 집계에서 제외하므로 결과에 영향을 주지
않습니다.
