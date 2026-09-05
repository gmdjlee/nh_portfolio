"""
Composite risk model for Korean equity exposure.

Six signals, each mapped to a risk-on score in [0, 1]. All signals are
computed from data available at close of day t and applied to the day t+1
return, so there is no look-ahead.

  1. breadth   : share of large-cap KOSPI names above their own 200d MA,
                 ranked into a trailing 3-year percentile
  2. trend     : index vs its own 200d MA, scaled by distance
  3. drawdown  : index drawdown from its trailing 2-year high (ladder)
  4. vol       : 20d realised volatility, inverted trailing percentile
  5. fx        : USD/KRW vs its 200d MA (won weakness = risk-off)
  6. global    : S&P 500 vs its 200d MA, lagged one day for the time zone
"""
import numpy as np
import pandas as pd

SELL_COST, BUY_COST = 0.0017, 0.0002
PCT_WIN = 756          # 3 years of trading days for percentile ranks


def _pct_rank(s, win=PCT_WIN):
    """Trailing percentile of the latest value within its own window."""
    return s.rolling(win, min_periods=252).rank(pct=True)


def build_signals(idx, stocks, fx=None, spx=None):
    """Return a DataFrame of risk-on scores in [0, 1], indexed like idx."""
    sig = pd.DataFrame(index=idx.index)

    # 1. breadth -------------------------------------------------------
    ma200 = stocks.rolling(200, min_periods=150).mean()
    above = (stocks > ma200) & stocks.notna() & ma200.notna()
    valid = (stocks.notna() & ma200.notna()).sum(axis=1)
    breadth = (above.sum(axis=1) / valid.where(valid >= 30)).reindex(idx.index).ffill()
    sig['breadth'] = _pct_rank(breadth)

    # 2. trend ---------------------------------------------------------
    m = idx.rolling(200, min_periods=150).mean()
    gap = (idx / m - 1.0)
    sig['trend'] = (gap / 0.10 + 0.5).clip(0, 1)      # +-10% band around the MA

    # 3. drawdown ladder ----------------------------------------------
    dd = idx / idx.rolling(504, min_periods=252).max() - 1.0
    sig['drawdown'] = (1.0 + dd / 0.10).clip(0, 1)    # 0% -> 1.0, -10% -> 0.0

    # 4. volatility ----------------------------------------------------
    rv = idx.pct_change().rolling(20).std() * np.sqrt(252)
    sig['vol'] = 1.0 - _pct_rank(rv)

    # 5. FX ------------------------------------------------------------
    if fx is not None:
        f = fx.reindex(idx.index).ffill()
        fgap = f / f.rolling(200, min_periods=150).mean() - 1.0
        sig['fx'] = (0.5 - fgap / 0.06).clip(0, 1)    # won weak -> risk-off

    # 6. global --------------------------------------------------------
    if spx is not None:
        s = spx.reindex(idx.index).ffill().shift(1)
        sgap = s / s.rolling(200, min_periods=150).mean() - 1.0
        sig['global'] = (sgap / 0.10 + 0.5).clip(0, 1)

    return sig.fillna(0.5)


def score_to_exposure(score, floor=0.25, cap=1.0, steps=8):
    """Quantise the composite score into discrete exposure levels."""
    e = floor + (cap - floor) * score
    return np.round(e * steps) / steps


def backtest(idx, exposure, band=0.05, sell=SELL_COST, buy=BUY_COST):
    """Apply exposure decided at close of t to the t+1 return."""
    p = idx.to_numpy(float)
    n = len(p)
    ret = np.zeros(n)
    ret[1:] = p[1:] / p[:-1] - 1.0
    tgt = exposure.to_numpy(float)

    V = np.ones(n)
    held = tgt[0]
    trades = 0
    ex_path = np.zeros(n)
    for t in range(1, n):
        V[t] = V[t - 1] * (1.0 + held * ret[t])
        want = tgt[t - 1]                 # decided on yesterday's close
        if abs(want - held) >= band:
            V[t] *= (1.0 - abs(want - held) * (sell if want < held else buy))
            held = want
            trades += 1
        ex_path[t] = held
    return pd.Series(V, index=idx.index), pd.Series(ex_path, index=idx.index), trades


def stats(v, rf=0.025):
    r = v.pct_change().dropna()
    yrs = len(v) / 252.0
    cagr = (v.iloc[-1] / v.iloc[0]) ** (1 / yrs) - 1
    dd = v / v.cummax() - 1
    mdd = dd.min()
    vol = r.std() * np.sqrt(252)
    down = r[r < 0].std() * np.sqrt(252)
    uw, best, cur = (dd < -1e-9).to_numpy(), 0, 0
    for x in uw:
        cur = cur + 1 if x else 0
        best = max(best, cur)
    return dict(CAGR=cagr, MDD=mdd, Vol=vol,
                Sharpe=(cagr - rf) / vol if vol else np.nan,
                Sortino=(cagr - rf) / down if down else np.nan,
                Calmar=cagr / abs(mdd) if mdd else np.nan,
                UW=best, Final=v.iloc[-1],
                WinYr=(v.resample('YE').last().pct_change().dropna() > 0).mean())
