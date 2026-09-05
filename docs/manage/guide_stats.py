"""밴드별 운영 통계를 산출합니다. 가이드북의 상황별 지침 근거가 됩니다."""
import json
import warnings

import numpy as np
import pandas as pd

warnings.filterwarnings('ignore')
from riskmodel import backtest, score_to_exposure, stats, _pct_rank

ks = pd.read_csv('idx.csv', index_col=0, parse_dates=True)['KS11']
st = pd.read_csv('stocks.csv', index_col=0, parse_dates=True).reindex(ks.index).ffill()


def breadth_raw(stocks, ma):
    m = stocks.rolling(ma, min_periods=int(ma * .75)).mean()
    ab = (stocks > m) & stocks.notna() & m.notna()
    vl = (stocks.notna() & m.notna()).sum(axis=1)
    return ab.sum(axis=1) / vl.where(vl >= 30)


ex = []
for ma in (150, 200, 250):
    p = _pct_rank(breadth_raw(st, ma))
    for sm in (20, 40, 60):
        ex.append(score_to_exposure(p.rolling(sm).mean().bfill().fillna(.5), floor=0.0))
ENS = np.round(pd.concat(ex, axis=1).mean(axis=1) * 8) / 8
nav, held, ntr = backtest(ks, ENS, band=0.15)

raw200 = breadth_raw(st, 200)
pctl = _pct_rank(raw200)
warm = ks.index >= ks.index[0] + pd.Timedelta(days=1200)   # 백분위 산출 가능 이후만 집계

# ---------------------------------------------------------------- 밴드 정의
GROUPS = [
    ('최대 방어', 0.000, 0.130, 'g1'),
    ('방어', 0.130, 0.440, 'g2'),
    ('중립', 0.440, 0.560, 'g3'),
    ('적극', 0.560, 0.815, 'g4'),
    ('최대 투입', 0.815, 1.001, 'g5'),
]

fwd = {h: ks.shift(-h) / ks - 1 for h in (63, 126, 252)}
bands = []
for nm, lo, hi, cid in GROUPS:
    sel = (held >= lo) & (held < hi) & warm
    n = int(sel.sum())
    # 연속 체류 구간 길이
    runs, cur = [], 0
    for x in sel.to_numpy():
        if x:
            cur += 1
        elif cur:
            runs.append(cur); cur = 0
    if cur:
        runs.append(cur)
    row = dict(name=nm, cid=cid, lo=lo, hi=hi, share=float(sel.mean()),
               days=n, spells=len(runs),
               median_spell=int(np.median(runs)) if runs else 0,
               max_spell=int(max(runs)) if runs else 0)
    for h, lab in ((63, 'm3'), (126, 'm6'), (252, 'm12')):
        f = fwd[h][sel].dropna()
        row[lab + '_med'] = float(f.median()) if len(f) else None
        row[lab + '_p25'] = float(f.quantile(.25)) if len(f) else None
        row[lab + '_p75'] = float(f.quantile(.75)) if len(f) else None
        row[lab + '_win'] = float((f > 0).mean()) if len(f) else None
    r = ks.pct_change()[sel].dropna()
    row['ann_vol'] = float(r.std() * np.sqrt(252))
    row['ann_ret'] = float(r.mean() * 252)
    bands.append(row)

# ---------------------------------------------------------------- 밴드 위험 분해
for b in bands:
    sel = (held >= b['lo']) & (held < b['hi']) & warm
    r = ks.pct_change()[sel].dropna()
    f = fwd[63][sel].dropna()
    b['ann_vol'] = float(r.std() * np.sqrt(252))
    b['down_vol'] = float(r[r < 0].std() * np.sqrt(252))
    b['p_drop10'] = float((f < -.10).mean()) if len(f) else None
    b['p_rise10'] = float((f > .10).mean()) if len(f) else None
    b['iqr3'] = float(f.quantile(.75) - f.quantile(.25)) if len(f) else None
    b['worst3'] = float(f.min()) if len(f) else None

# ---------------------------------------------------------------- 국면별 상대성과
r12 = nav.pct_change(252).dropna()
b12 = ks.pct_change(252).reindex(r12.index)
rel = (r12 - b12).dropna()
regime = []
for lo, hi, lab in [(-9, -.20, '지수 -20% 미만'), (-.20, -.05, '지수 -20 ~ -5%'),
                    (-.05, .10, '지수 -5 ~ +10%'), (.10, .30, '지수 +10 ~ +30%'),
                    (.30, 9, '지수 +30% 초과')]:
    m = (b12 >= lo) & (b12 < hi)
    if m.sum() > 20:
        regime.append(dict(label=lab, share=float(m.mean()),
                           med=float(rel[m].median()), win=float((rel[m] > 0).mean())))
relq = {k: float(rel.quantile(q)) for k, q in
        [('p05', .05), ('p25', .25), ('p50', .5), ('p75', .75), ('p95', .95)]}
relq['lag_prob'] = float((rel < 0).mean())
relq['win_in_down'] = float((rel[b12 < -0.10] > 0).mean())

# ---------------------------------------------------------------- 운영 부하
chg = held.diff().abs() > 1e-9
per_year = chg[warm].resample('YE').sum()
ops = dict(total_trades=int(ntr), per_year_mean=float(per_year.mean()),
           per_year_max=int(per_year.max()), per_year_min=int(per_year.min()),
           zero_years=int((per_year == 0).sum()), n_years=int(len(per_year)))

# ---------------------------------------------------------------- 실패 판정 기준
dd = nav / nav.cummax() - 1
roll12 = nav.pct_change(252).dropna()
r = nav.pct_change()
roll3y_sharpe = (r.rolling(756).mean() * 252 - 0.025) / (r.rolling(756).std() * np.sqrt(252))
bh12 = ks.pct_change(252).dropna()
uw, best, cur = (dd < -1e-9).to_numpy(), 0, 0
for x in uw:
    cur = cur + 1 if x else 0
    best = max(best, cur)
tw = dict(mdd=float(dd.min()), worst12=float(roll12.min()),
          p05_12=float(roll12.quantile(.05)), median12=float(roll12.median()),
          longest_uw=int(best), min_roll3y_sharpe=float(roll3y_sharpe.min()),
          pct_roll3y_neg=float((roll3y_sharpe.dropna() < 0).mean()),
          worst_lag=float((roll12 - bh12.reindex(roll12.index)).min()),
          pct_lag_bh=float((roll12 < bh12.reindex(roll12.index)).mean()))

# ---------------------------------------------------------------- 현재 상태 / 검증값
recent = pd.DataFrame({'kospi': ks, 'raw': raw200, 'pctl': pctl, 'expo': held}).tail(6)
now = dict(date=str(ks.index[-1].date()), kospi=float(ks.iloc[-1]),
           raw=float(raw200.iloc[-1]), pctl=float(pctl.iloc[-1]),
           expo=float(held.iloc[-1]), target=float(ENS.iloc[-1]),
           checks=[dict(d=str(i.date()), k=float(r0.kospi), raw=float(r0.raw),
                        p=float(r0.pctl), e=float(r0.expo))
                   for i, r0 in recent.iterrows()])

# 전환 사례: 실제로 발생한 최근 재조정 10건
ch = held[chg]
trans = []
prev = held.shift(1)
for d in ch.index[-10:]:
    trans.append(dict(d=str(d.date()), frm=float(prev.loc[d]), to=float(held.loc[d]),
                      kospi=float(ks.loc[d])))

json.dump(dict(bands=bands, ops=ops, tripwires=tw, now=now, trans=trans,
               regime=regime, relq=relq),
          open('guide.json', 'w'), ensure_ascii=False)

print('밴드별 통계')
for b in bands:
    print('  %-8s 비중%3.0f-%3.0f%%  체류%5.1f%%  구간%3d회 중앙%4d일 최장%4d일 | 3개월후 중앙%+6.1f%% 상승률%3.0f%% | 12개월후 중앙%+6.1f%%'
          % (b['name'], b['lo'] * 100, b['hi'] * 100, b['share'] * 100, b['spells'],
             b['median_spell'], b['max_spell'], b['m3_med'] * 100, b['m3_win'] * 100, b['m12_med'] * 100))
print('\n운영부하: 총 %d회, 연평균 %.1f회 (최대 %d, 최소 %d, 무거래 %d개년/%d개년)'
      % (ops['total_trades'], ops['per_year_mean'], ops['per_year_max'], ops['per_year_min'],
         ops['zero_years'], ops['n_years']))
print('\n실패판정 기준선: MDD %.2f%%, 최악12개월 %.2f%%, 하위5%% 12개월 %.2f%%, 최장수평 %d일, 롤링3년Sharpe 최저 %.2f (음수비중 %.1f%%)'
      % (tw['mdd'] * 100, tw['worst12'] * 100, tw['p05_12'] * 100, tw['longest_uw'],
         tw['min_roll3y_sharpe'], tw['pct_roll3y_neg'] * 100))
print('단순보유 대비 12개월 열위 확률 %.1f%%, 최악 열위폭 %.2f%%p'
      % (tw['pct_lag_bh'] * 100, tw['worst_lag'] * 100))
print('\n현재: 백분위 %.1f%%, 원지표 %.1f%%, 비중 %.0f%%' % (now['pctl'] * 100, now['raw'] * 100, now['expo'] * 100))
