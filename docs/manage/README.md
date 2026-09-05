# 한국 주식 익스포저 관리 모델 — 검증 자료

## 실행 순서

```bash
pip install FinanceDataReader yfinance pandas numpy
python3 fetch_data.py          # 지수·개별종목 수집 (약 3분)

# 검증 보고서
python3 make_report_data.py    # 전 전략 재집계 → report.json
python3 build_report.py        # report.json → risk_model_report.html

# 운영 가이드북 (주 1회 갱신하여 오늘의 판정을 최신화)
python3 guide_stats.py         # 밴드별 운영 통계 → guide.json
python3 build_guide.py         # guide.json → operating_guidebook.html
```

## 파일

| 파일 | 내용 |
|---|---|
| `operating_guidebook.html` | 실전 운영 가이드북. 오늘의 판정, 밴드별 지침, 상황별 판단, 폐기 기준을 담았습니다. |
| `risk_model_report.html` | 검증 보고서. 전략 13종 비교와 백테스트 근거입니다. |
| `guide_stats.py` | 밴드별 체류·위험·국면별 상대성과 통계 산출 |
| `build_guide.py` | 가이드북 HTML 생성기 |
| `riskmodel.py` | 시장 폭 신호 산출, 익스포저 매핑, 백테스트 엔진 |
| `sweep.py` | 낙폭 사다리 파라미터 전역 탐색기 (1,470개 조합) |
| `ladder2.py` | 낙폭 사다리 v2 (기준점 전환 가능), 이동평균 필터 벤치마크 |
| `vt.py` | 변동성 타기팅 벤치마크 |
| `make_report_data.py` | 전 전략 동일 기준 재집계 및 보고서 데이터 생성 |
| `build_report.py` | HTML 생성기 (SVG 차트를 직접 그립니다) |
| `fetch_data.py` | 데이터 수집 |

## 최종 전략 요약

코스피 시가총액 상위 250종목 중 자기 200일 이동평균을 웃도는 종목 비율을
3년 백분위로 환산하여 주식 편입비중으로 사용합니다. 이동평균 150/200/250일과
평활 20/40/60일을 교차한 9개 구성의 평균을 취하고, 목표 비중과 현재 비중의
차이가 15%p 이상일 때만 재조정합니다.

2004-01-02 ~ 2026-09-04 기준: CAGR 9.26%, 최대낙폭 -19.89%, Sharpe 0.52,
Calmar 0.47, 매매 64회. 단순 보유는 CAGR 9.90%, 최대낙폭 -54.54%, Sharpe 0.33.

## 운영 요약

주 1회, 같은 요일 장 마감 후에 `guide_stats.py` 와 `build_guide.py` 를 실행하면
가이드북 상단에 그 주의 판정이 갱신됩니다. 목표 비중과 현재 비중의 차이가
15%p 미만이면 아무것도 하지 않습니다.

검증 기간 연평균 실행 횟수는 3.0회이며, 한 해에 한 번도 실행하지 않은 해도 있었습니다.
