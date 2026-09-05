"""guide.json 을 읽어 실전 운영 가이드북 HTML 을 생성합니다. 외부 의존성이 없습니다."""
import json

G = json.load(open('guide.json'))
B, OPS, TW, NOW, REG, RQ = G['bands'], G['ops'], G['tripwires'], G['now'], G['regime'], G['relq']
CUR = next(b for b in B if b['lo'] <= NOW['expo'] < b['hi'])
TGT = next(b for b in B if b['lo'] <= NOW['target'] < b['hi'])
GAP = (NOW['target'] - NOW['expo']) * 100
if abs(GAP) < 15:
    ACT, ACTK = '실행하지 않습니다', 'hold'
    ACTD = ('목표 %.1f%%와 유지 비중 %.0f%%의 차이가 %.1f%%p로 15%%p 기준에 미달합니다.'
            % (NOW['target'] * 100, NOW['expo'] * 100, abs(GAP)))
elif GAP < 0:
    ACT, ACTK = '비중을 %.1f%%로 줄입니다' % (NOW['target'] * 100), 'cut'
    ACTD = '차이 %.1f%%p. 당일 또는 익일에 한 번에 실행합니다.' % abs(GAP)
else:
    ACT, ACTK = '비중을 %.1f%%로 늘립니다' % (NOW['target'] * 100), 'go'
    ACTD = '차이 %.1f%%p. 3영업일 간격으로 3회에 나누어 채웁니다.' % GAP

CHAR = {
    '최대 방어': ('내부가 무너진 상태입니다', '지수가 버티고 있어도 대형주 다수가 추세를 잃었습니다. '
                'ㅤ이 밴드의 3개월 변동성은 30.7%로 가장 높고, 3개월 안에 10% 넘게 빠질 확률이 29%입니다.'),
    '방어': ('위험이 쌓이는 중입니다', '아직 붕괴는 아니지만 종목 간 편차가 벌어지고 있습니다. '
           'ㅤ변동성 24.0%로 평시보다 높고, 여기서 더 나빠지면 최대 방어로 넘어갑니다.'),
    '중립': ('가장 흔한 평상 상태입니다', '전체 기간의 30.5%를 차지합니다. 변동성 17.5%로 가장 낮고 '
           'ㅤ3개월 내 10% 하락 확률도 7%에 그칩니다. 특별히 할 일이 없는 구간입니다.'),
    '적극': ('시장 내부가 건강합니다', '대형주 다수가 자기 추세 위에 있습니다. 3개월 내 10% 하락 확률이 '
           'ㅤ2%로 전 밴드 중 가장 낮습니다.'),
    '최대 투입': ('회복 초기일 가능성이 높습니다', '변동성은 29.1%로 높지만 방향이 위쪽으로 치우쳐 있습니다. '
              'ㅤ3개월 내 10% 상승 확률 44%, 하락 확률 4%입니다. 급락 직후에 주로 나타납니다.'),
}
DO = {
    '최대 방어': ['목표 비중까지 즉시 줄입니다. 반등을 기다리지 않습니다.',
              '남길 종목은 상대강도 상위 1~2개로 한정합니다.',
              '확보한 현금은 그대로 둡니다. 채권이나 대체자산으로 돌리지 않습니다.'],
    '방어': ['상대강도 하위 종목부터 정리합니다.', '신규 종목 편입을 중단합니다.',
           '보유 종목의 -8% 손절선을 다시 확인합니다.'],
    '중립': ['비중을 그대로 둡니다.', '개별 종목 손절선만 관리합니다.',
           '분기 유니버스 갱신 등 정기 작업을 처리하기 좋은 시기입니다.'],
    '적극': ['목표 비중까지 3영업일 간격으로 3회에 나누어 채웁니다.',
           '하락기에 상대강도를 지킨 종목과 신고가를 경신하는 종목을 우선합니다.',
           '살 종목이 마땅치 않으면 지수 ETF로 채웁니다.'],
    '최대 투입': ['목표 비중까지 채웁니다. 이 구간은 평균 70일 지속되었습니다.',
              '직전 하락기에 손실을 준 종목이 아니라 새로 부상하는 주도주를 담습니다.',
              '레버리지는 사용하지 않습니다. 상한은 100%입니다.'],
}
DONT = {
    '최대 방어': ['지수가 오른다는 이유로 비중을 늘리지 않습니다.', '평균 단가를 낮추는 추가 매수를 하지 않습니다.'],
    '방어': ['하락 종목을 더 사서 물타기하지 않습니다.', '“곧 반등할 것”이라는 판단으로 매도를 미루지 않습니다.'],
    '중립': ['차이가 15%p 미만인데 재조정하지 않습니다.', '지루하다는 이유로 종목을 교체하지 않습니다.'],
    '적극': ['한 번에 전액을 투입하지 않습니다.', '상한 100%를 넘겨 신용이나 레버리지를 쓰지 않습니다.'],
    '최대 투입': ['늦었다고 판단하여 건너뛰지 않습니다.', '이 구간이 계속될 것으로 가정하고 계획을 세우지 않습니다.'],
}
QA = [
    ('백분위는 낮은데 지수가 신고가를 경신하고 있습니다.',
     '정상적인 상황이며 밴드를 그대로 유지합니다. 이 신호는 지수의 방향을 예측하지 않습니다. '
     '실제로 최대 방어 밴드에서 3개월 뒤 지수는 중앙값 +3.1%로 올랐고 상승 확률도 59%였습니다. '
     '신호가 알려 주는 것은 결과의 폭입니다. 같은 밴드에서 3개월 안에 10% 넘게 빠진 경우가 29%, 최악은 -42.3%였습니다. '
     '오를 수도 있지만 크게 틀어질 수도 있는 구간이므로 비중을 줄이는 것입니다.'),
    ('목표 비중과 현재 비중의 차이가 12%p입니다.',
     '실행하지 않습니다. 15%p 기준에 미달하면 아무것도 하지 않는 것이 규칙입니다. '
     '이 기준이 없으면 신호가 흔들릴 때마다 매매가 발생하여 거래비용만 쌓입니다.'),
    ('어제 재조정했는데 오늘 신호가 반대로 움직였습니다.',
     '15%p 기준이 이미 이 문제를 막아 줍니다. 되돌리려면 신호가 15%p만큼 반대로 움직여야 하는데, '
     '20~60일 평활을 거친 신호에서는 하루 만에 그 폭이 나오지 않습니다. 규칙대로 두시면 됩니다.'),
    ('개별 종목이 -8%에 닿았는데 시장 신호는 적극입니다.',
     '해당 종목은 매도하고, 확보된 자금으로 다른 종목을 사서 전체 비중을 유지합니다. '
     '두 계층은 독립적으로 작동합니다. 종목 손절은 그 종목의 문제를 처리하고, 시장폭 신호는 전체 비중을 결정합니다.'),
    ('급락이 진행 중인데 시장폭 신호가 아직 바뀌지 않았습니다.',
     '개별 종목 -8% 손절이 먼저 작동하도록 설계되어 있습니다. 시장폭 신호는 평활 때문에 며칠 늦게 반응합니다. '
     '이 지연은 잦은 매매를 막기 위해 의도적으로 넣은 것이며, 백테스트 성과에 이미 반영되어 있습니다.'),
    ('12개월째 단순 보유보다 뒤처지고 있습니다.',
     '설계된 결과이며 폐기 사유가 아닙니다. 검증 기간에서 12개월 단위로 단순 보유에 뒤처진 경우가 '
     f'{RQ["lag_prob"]*100:.0f}%였습니다. 뒤처지는 것이 기본 상태이고, 지수가 크게 빠지는 소수 국면에서 그 차이를 한 번에 되찾는 구조입니다.'),
    ('데이터를 받지 못했거나 계산할 시간이 없었습니다.',
     '직전 판정을 그대로 유지합니다. 20~60일 평활을 거친 신호이므로 하루 이틀 지연으로 결과가 달라지지 않습니다. '
     '주 1회만 계산해도 충분합니다.'),
    ('비중을 늘려야 하는데 살 만한 종목이 보이지 않습니다.',
     '지수 ETF로 채웁니다. 비중 규칙이 종목 선택보다 우선합니다. '
     '종목을 고르지 못해 비중을 비워 두면 규칙을 지키지 않은 것이 됩니다.'),
    ('배당락이나 유상증자로 평가액이 달라졌습니다.',
     '비중은 항상 당일 시가평가액 기준으로 계산합니다. 유입된 현금은 다음 재조정 시점에 반영합니다. '
     '이 때문에 별도로 매매하지 않습니다.'),
    ('백분위가 0%까지 떨어졌습니다.',
     '밴드가 지시하는 비중을 따릅니다. 최종 비중은 9개 구성의 평균이므로 개별 지표가 0%여도 전체 비중이 0%가 되는 경우는 드뭅니다. '
     f'검증 기간에서 최대 방어 밴드 체류 비율은 {B[0]["share"]*100:.1f}%, 중앙 지속 기간은 {B[0]["median_spell"]}일이었습니다.'),
    ('거래비용과 세금이 부담됩니다.',
     f'연평균 {OPS["per_year_mean"]:.1f}회입니다. 검증에는 매도 0.17%, 매수 0.02%를 이미 반영했습니다. '
     '차이가 15%p 미만일 때 실행하지 않는 규칙이 비용을 억제하는 장치입니다.'),
    ('신호를 무시하고 제 판단으로 결정하고 싶습니다.',
     '그렇게 하고 싶어지는 순간이 통계적으로 가장 위험한 시점입니다. 재량 개입은 검증 대상이 아니므로 '
     '어떤 성과도 보장되지 않으며, 이 문서의 모든 수치가 그 순간부터 무효가 됩니다. '
     '규칙을 바꾸시려면 매매 도중이 아니라 별도의 검증 절차를 거쳐 바꾸시기 바랍니다.'),
]
NEVER = [
    ('하락 중 평균 단가를 낮추는 추가 매수',
     '검증 과정에서 -20% 방어선이 무너지는 가장 흔한 경로였습니다. 밴드가 내려간 상태에서는 어떤 종목도 추가 매수하지 않습니다.'),
    ('손실을 빨리 만회하기 위한 레버리지 또는 신용 사용',
     '주식 비중 상한은 100%입니다. -20%를 회복하려면 +25%가 필요한데, 레버리지로 시도하면 두 번째 -20%가 -40%가 됩니다.'),
    ('신호가 낮은 구간에서 재량으로 비중을 늘리는 행위',
     '이 밴드는 3개월 내 10% 이상 하락 확률이 29%인 구간입니다. 확률이 낮다는 것이지 안전하다는 뜻이 아닙니다.'),
    ('차이가 15%p 미만인데 실행하는 재조정',
     '거래비용만 누적되고 성과는 개선되지 않습니다. 이 기준을 없애면 연 매매가 세 자릿수로 늘어납니다.'),
    ('밴드를 건너뛰어 0%와 100%를 한 번에 오가는 전환',
     '단계적 조정이 이 전략의 핵심입니다. 전량 청산은 회복 국면 참여 수단을 완전히 제거합니다.'),
]
TRIP = [
    ('메커니즘 고장', '지수가 12개월간 20% 이상 하락한 구간에서 모델이 지수를 밑도는 경우',
     f'검증 기간 중 지수가 12개월간 20% 넘게 하락한 국면(전체의 {REG[0]["share"]*100:.1f}%)에서 '
     '모델이 우위였던 비율은 100%였습니다. 이 조건이 깨지면 시장폭과 위험의 관계 자체가 '
     '무너진 것이므로 즉시 운용을 중단하고 재검증하셔야 합니다.', 'crit'),
    ('낙폭 초과', '최대 낙폭이 -30%를 넘어서는 경우',
     f'검증 기간 최악은 {TW["mdd"]*100:.2f}%였습니다. -30%는 그 1.5배에 해당하므로, '
     '이를 넘으면 신호가 위험 국면을 놓치고 있다고 판단합니다.', 'crit'),
    ('12개월 손실 초과', '12개월 수익률이 -20% 아래로 내려가는 경우',
     f'검증 기간 최악의 12개월은 {TW["worst12"]*100:.2f}%, 하위 5% 지점은 {TW["p05_12"]*100:.2f}%였습니다.', 'warn'),
    ('신호 노이즈화', '연간 재조정이 15회를 넘는 경우',
     f'검증 기간 연 최대는 {OPS["per_year_max"]}회였습니다. 두 배를 넘어서면 백분위가 기준선 부근에서 '
     '진동한다는 뜻이므로 평활 기간을 늘려야 합니다.', 'warn'),
]
NOT_TRIP = [
    ('단순 보유보다 뒤처짐', f'12개월 기준 {RQ["lag_prob"]*100:.0f}%의 기간에서 정상적으로 발생했습니다.'),
    ('3년 누적 성과가 부진함', f'3년 롤링 Sharpe가 음수였던 기간이 {TW["pct_roll3y_neg"]*100:.0f}%입니다.'),
    ('강세장에서 크게 뒤처짐', f'지수가 12개월간 30% 넘게 오른 국면에서 중앙값 {REG[-1]["med"]*100:.1f}%p 뒤처졌습니다. 절반의 비중만 싣기 때문입니다.'),
    ('오랫동안 전고점을 회복하지 못함', f'검증 기간 최장 수평 구간은 {TW["longest_uw"]}거래일, 약 {TW["longest_uw"]/252:.1f}년이었습니다.'),
]


def pct(v, dp=1, sign=False):
    f = '%+.*f%%' if sign else '%.*f%%'
    return f % (dp, v * 100)


def cls(v):
    return 'up' if v > 0 else ('down' if v < 0 else '')


# --------------------------------------------------------------- 밴드 사다리 SVG
def ladder(cur_lo):
    w, h = 1000, 92
    L, R = 8, 8
    segs, x = [], L
    tot = w - L - R
    for b in B:
        wd = tot * (b['hi'] - b['lo']) / 1.0
        on = abs(b['lo'] - cur_lo) < 1e-9
        segs.append(
            '<g class="seghit" data-cid="%s" role="link" tabindex="0">'
            '<rect class="seg %s%s" x="%.1f" y="20" width="%.1f" height="34" rx="3"/>'
            '<text class="segn%s" x="%.1f" y="42" text-anchor="middle">%s</text>'
            '<text class="segp" x="%.1f" y="70" text-anchor="middle">%d–%d%%</text></g>'
            % (b['cid'], b['cid'], ' on' if on else '', x + 1.5, wd - 3,
               ' on' if on else '', x + wd / 2, b['name'],
               x + wd / 2, round(b['lo'] * 100), round(b['hi'] * 100 - 0.1)))
        if on:
            segs.insert(0, '<polygon class="mark" points="%.1f,4 %.1f,16 %.1f,16"/>'
                        % (x + wd / 2, x + wd / 2 - 7, x + wd / 2 + 7))
        x += wd
    wide = ('<svg viewBox="0 0 %d %d" class="ladder wide" role="img" '
            'aria-label="주식 편입비중 제어 밴드">%s</svg>' % (w, h, ''.join(segs)))
    rows = []
    for b in B:
        on = abs(b['lo'] - cur_lo) < 1e-9
        rows.append(
            '<li class="vb %s%s" data-cid="%s" role="link" tabindex="0">'
            '<span class="vb-n">%s</span><span class="vb-p">%d–%d%%</span>%s</li>'
            % (b['cid'], ' on' if on else '', b['cid'], b['name'],
               round(b['lo'] * 100), round(b['hi'] * 100 - 0.1),
               '<span class="vb-m">현재</span>' if on else ''))
    return wide + '<ul class="ladder-v" aria-label="주식 편입비중 제어 밴드">%s</ul>' % ''.join(rows)


band_cards = ''.join(
    f'''<article class="bc {b['cid']}" id="band-{b['cid']}">
  <header class="bc-h">
    <span class="chip {b['cid']}">{b['name']}</span>
    <span class="bc-r">주식 비중 {round(b['lo']*100)}–{round(b['hi']*100-0.1)}%</span>
  </header>
  <p class="bc-lead"><b>{CHAR[b['name']][0]}</b> {CHAR[b['name']][1].replace('ㅤ','')}</p>
  <div class="bc-body">
    <div class="bc-col">
      <h4>해야 할 일</h4>
      <ul class="do">{''.join('<li>%s</li>' % d for d in DO[b['name']])}</ul>
      <h4>하지 말아야 할 일</h4>
      <ul class="dont">{''.join('<li>%s</li>' % d for d in DONT[b['name']])}</ul>
    </div>
    <div class="bc-col">
      <h4>이 밴드에서 실제로 일어난 일</h4>
      <table class="kv"><tbody>
        <tr><td>전체 기간 중 체류</td><td>{pct(b['share'])}</td></tr>
        <tr><td>한 번 들어가면</td><td>중앙 {b['median_spell']}일 · 최장 {b['max_spell']}일</td></tr>
        <tr><td>연환산 변동성</td><td>{pct(b['ann_vol'])}</td></tr>
        <tr><td>3개월 내 10% 하락</td><td class="{'down' if b['p_drop10']>0.15 else ''}">{pct(b['p_drop10'],0)}</td></tr>
        <tr><td>3개월 내 10% 상승</td><td class="{'up' if b['p_rise10']>0.3 else ''}">{pct(b['p_rise10'],0)}</td></tr>
        <tr><td>3개월 최악 사례</td><td class="down">{pct(b['worst3'])}</td></tr>
        <tr><td>3개월 뒤 지수 중앙값</td><td class="{cls(b['m3_med'])}">{pct(b['m3_med'],1,True)}</td></tr>
      </tbody></table>
    </div>
  </div>
</article>''' for b in B)

qa_items = ''.join(
    '<details class="qa"><summary>%s</summary><div class="qa-a"><p>%s</p></div></details>'
    % (q, a) for q, a in QA)

never_items = ''.join(
    '<li><b>%s</b><span>%s</span></li>' % (t, d) for t, d in NEVER)

trip_rows = ''.join(
    '<tr class="%s"><td class="nm">%s</td><td class="cond">%s</td><td class="why">%s</td></tr>'
    % (c, t, cond, why) for t, cond, why, c in TRIP)

nottrip_items = ''.join(
    '<li><b>%s</b><span>%s</span></li>' % (t, d) for t, d in NOT_TRIP)

regime_rows = ''.join(
    '<tr%s><td class="nm">%s</td><td class="dim">%s</td><td class="%s">%s</td>'
    '<td class="%s">%s</td></tr>'
    % (' class="winrow"' if r['win'] > .5 else '', r['label'], pct(r['share']),
       cls(r['med']), pct(r['med'], 1, True),
       'up' if r['win'] > .5 else 'down', pct(r['win'], 0))
    for r in REG)

check_rows = ''.join(
    '<tr><td class="nm">%s</td><td>%s</td><td>%s</td><td>%s</td><td class="hl">%d%%</td></tr>'
    % (c['d'], '%.2f' % c['k'], pct(c['raw']), pct(c['p']), round(c['e'] * 100))
    for c in NOW['checks'])

BANDS_JS = json.dumps([{'name': b['name'], 'lo': b['lo'], 'hi': b['hi'], 'cid': b['cid']} for b in B],
                      ensure_ascii=False)

HTML = f"""<!doctype html>
<html lang="ko"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>시장폭 익스포저 모델 · 운영 가이드북</title>
<style>
:root{{
  --ink:#17191F; --mute:#5A626F; --paper:#fff; --panel:#F4F6F9; --rule:#D5DAE2;
  --navy:#0D2438; --rise:#A82D26; --fall:#14427F;
  --g1:#14427F; --g2:#4A7AB8; --g3:#8A9199; --g4:#C4726A; --g5:#A82D26;
  --g1b:#EAF0F8; --g2b:#EEF3FA; --g3b:#F3F4F6; --g4b:#FBF0EF; --g5b:#F9ECEB;
}}
*{{box-sizing:border-box}}
html{{-webkit-text-size-adjust:100%;scroll-behavior:smooth;scroll-padding-top:96px}}
body{{margin:0;background:var(--paper);color:var(--ink);
  font-family:'Pretendard',-apple-system,'Apple SD Gothic Neo','Malgun Gothic','Noto Sans KR',sans-serif;
  font-size:16px;line-height:1.72;font-variant-numeric:tabular-nums;
  letter-spacing:-.005em;word-break:keep-all;overflow-wrap:break-word}}
.wrap{{max-width:1020px;margin:0 auto;padding:0 24px}}
.prose{{max-width:66ch}}
h1{{font-size:2.3rem;line-height:1.26;font-weight:800;letter-spacing:-.032em;margin:.15em 0 .3em}}
h2{{font-size:1.5rem;font-weight:750;letter-spacing:-.022em;margin:3.6rem 0 .6rem;
  padding-top:1.3rem;border-top:2px solid var(--navy)}}
h2 .no{{color:var(--mute);font-weight:600;margin-right:.5em;font-size:.82em}}
h3{{font-size:1.08rem;font-weight:700;margin:2.2rem 0 .5rem;letter-spacing:-.015em}}
h4{{font-size:.8rem;font-weight:750;color:var(--mute);margin:1.1rem 0 .4rem}}
h4:first-child{{margin-top:0}}
p{{margin:.7rem 0}}

/* 고정 판정 바 */
.bar{{position:sticky;top:0;z-index:30;background:var(--navy);color:#fff;
  box-shadow:0 1px 0 rgba(0,0,0,.2)}}
.bar .wrap{{display:flex;align-items:center;gap:18px;padding-top:9px;padding-bottom:9px;flex-wrap:wrap}}
.bar .lab{{font-size:.76rem;color:#9FB3C8;font-weight:600}}
.bar .big{{font-size:1.32rem;font-weight:800;letter-spacing:-.02em;line-height:1.2}}
.bar .sep{{width:1px;height:26px;background:rgba(255,255,255,.22)}}
.bar .dt{{margin-left:auto;font-size:.76rem;color:#9FB3C8;text-align:right}}
.bar .act .big{{padding-left:11px;border-left:4px solid #9FB3C8}}
.bar .act.go .big{{border-left-color:#E8867E}}
.bar .act.cut .big{{border-left-color:#7FA8DE}}
.bar .act.hold .big{{border-left-color:#9FB3C8}}

header.top{{padding:46px 0 6px}}
.lede{{font-size:1.13rem;color:var(--mute);max-width:60ch}}

/* 밴드 사다리 */
.ladder{{width:100%;height:auto;display:block;margin:26px 0 6px}}
.seg{{opacity:.3}} .seg.on{{opacity:1}}
.seg.g1{{fill:var(--g1)}} .seg.g2{{fill:var(--g2)}} .seg.g3{{fill:var(--g3)}}
.seg.g4{{fill:var(--g4)}} .seg.g5{{fill:var(--g5)}}
.segn{{fill:#5A626F;font-size:13px;font-weight:700}}
.segn.on{{fill:#fff}}
.segp{{fill:#5A626F;font-size:11.5px}}
.mark{{fill:var(--navy)}}
.seghit{{cursor:pointer}}
.seghit:hover .seg{{opacity:.75}} .seghit:hover .seg.on{{opacity:1}}
.seghit:focus-visible{{outline:2px solid var(--navy);outline-offset:2px}}
.ladder-v{{display:none;list-style:none;padding:0;margin:24px 0 6px}}
.vb{{display:flex;align-items:center;gap:12px;padding:12px 16px;margin-bottom:6px;
  border-radius:4px;cursor:pointer;color:#fff;opacity:.34}}
.vb.on{{opacity:1}}
.vb.g1{{background:var(--g1)}} .vb.g2{{background:var(--g2)}} .vb.g3{{background:var(--g3)}}
.vb.g4{{background:var(--g4)}} .vb.g5{{background:var(--g5)}}
.vb-n{{font-weight:750;font-size:1rem}}
.vb-p{{font-size:.85rem;opacity:.88}}
.vb-m{{margin-left:auto;font-size:.76rem;font-weight:700;background:rgba(255,255,255,.24);
  padding:2px 9px;border-radius:3px}}
.vb:focus-visible{{outline:2px solid var(--navy);outline-offset:2px}}

/* 계산기 */
.calc{{border:1.5px solid var(--navy);border-radius:6px;overflow:hidden;margin:26px 0 8px}}
.calc-h{{background:var(--navy);color:#fff;padding:10px 20px;font-weight:700;font-size:.95rem}}
.calc-b{{padding:20px;display:grid;grid-template-columns:1fr 1fr 1.5fr;gap:22px;align-items:start}}
.fld label{{display:block;font-size:.8rem;color:var(--mute);font-weight:650;margin-bottom:6px}}
.fld input{{width:100%;font:inherit;font-size:1.3rem;font-weight:700;padding:8px 12px;
  border:1px solid var(--rule);border-radius:4px;background:var(--panel);color:var(--ink)}}
.fld input:focus{{outline:2px solid var(--navy);outline-offset:1px;background:#fff}}
.fld .hint{{font-size:.76rem;color:var(--mute);margin-top:5px}}
#verdict{{border-left:4px solid var(--rule);padding:2px 0 2px 16px}}
#verdict .vt{{font-size:1.24rem;font-weight:800;letter-spacing:-.02em;line-height:1.3}}
#verdict .vd{{font-size:.88rem;color:var(--mute);margin-top:5px}}
#verdict.go{{border-left-color:var(--rise)}} #verdict.go .vt{{color:var(--rise)}}
#verdict.hold{{border-left-color:var(--g3)}}
#verdict.cut{{border-left-color:var(--fall)}} #verdict.cut .vt{{color:var(--fall)}}

/* 절차 */
ol.steps{{counter-reset:s;list-style:none;padding:0;margin:18px 0}}
ol.steps li{{counter-increment:s;position:relative;padding:0 0 0 46px;margin:0 0 18px}}
ol.steps li::before{{content:counter(s);position:absolute;left:0;top:1px;width:28px;height:28px;
  border-radius:50%;background:var(--navy);color:#fff;font-size:.86rem;font-weight:700;
  display:flex;align-items:center;justify-content:center}}
ol.steps b{{display:block;font-weight:700}}
ol.steps span{{color:var(--mute);font-size:.93rem}}

/* 밴드 카드 */
.bc{{border:1px solid var(--rule);border-radius:6px;margin:16px 0;overflow:hidden}}
.bc.g1{{border-left:5px solid var(--g1)}} .bc.g2{{border-left:5px solid var(--g2)}}
.bc.g3{{border-left:5px solid var(--g3)}} .bc.g4{{border-left:5px solid var(--g4)}}
.bc.g5{{border-left:5px solid var(--g5)}}
.bc-h{{display:flex;align-items:center;gap:14px;padding:12px 20px;border-bottom:1px solid var(--rule)}}
.bc.g1 .bc-h{{background:var(--g1b)}} .bc.g2 .bc-h{{background:var(--g2b)}}
.bc.g3 .bc-h{{background:var(--g3b)}} .bc.g4 .bc-h{{background:var(--g4b)}}
.bc.g5 .bc-h{{background:var(--g5b)}}
.chip{{display:inline-block;padding:3px 12px;border-radius:3px;color:#fff;font-size:.86rem;font-weight:750}}
.chip.g1{{background:var(--g1)}} .chip.g2{{background:var(--g2)}} .chip.g3{{background:var(--g3)}}
.chip.g4{{background:var(--g4)}} .chip.g5{{background:var(--g5)}}
.bc-r{{margin-left:auto;font-size:.86rem;color:var(--mute);font-weight:650}}
.bc-lead{{padding:14px 20px 0;margin:0;font-size:.95rem;max-width:74ch}}
.bc-body{{display:grid;grid-template-columns:1.25fr 1fr;gap:28px;padding:12px 20px 20px}}
ul.do,ul.dont{{list-style:none;padding:0;margin:0}}
ul.do li,ul.dont li{{position:relative;padding-left:22px;margin:.3rem 0;font-size:.92rem}}
ul.do li::before{{content:"";position:absolute;left:2px;top:.62em;width:9px;height:9px;
  border-radius:2px;background:var(--g5)}}
ul.dont li::before{{content:"";position:absolute;left:2px;top:.62em;width:9px;height:9px;
  border-radius:2px;background:var(--g1)}}
table.kv{{width:100%;border-collapse:collapse;font-size:.85rem}}
table.kv td{{padding:5px 0;border-bottom:1px solid var(--rule)}}
table.kv td:last-child{{text-align:right;font-weight:650}}
table.kv td:first-child{{color:var(--mute)}}

/* 일반 표 */
.scroll{{overflow-x:auto;margin:16px 0 4px}}
table.g{{border-collapse:collapse;width:100%;font-size:.88rem;min-width:520px}}
table.g th,table.g td{{padding:9px 12px;text-align:right;border-bottom:1px solid var(--rule);white-space:nowrap}}
table.g th{{font-size:.78rem;font-weight:650;color:var(--mute);border-bottom:1.5px solid var(--navy)}}
table.g th:first-child,table.g td:first-child{{text-align:left}}
table.g td.nm{{font-weight:650}} table.g td.cond,table.g td.why{{text-align:left;white-space:normal}}
table.g td.why{{color:var(--mute);font-size:.85rem;line-height:1.6}}
table.g tr.winrow td{{background:#FBF3F2}}
table.g tr.crit td.nm{{color:var(--rise)}} table.g tr.warn td.nm{{color:var(--fall)}}
td.hl{{font-weight:750;color:var(--navy)}}
.up{{color:var(--rise)}} .down{{color:var(--fall)}} .dim{{color:var(--mute)}}

/* 상황별 판단 */
details.qa{{border-bottom:1px solid var(--rule)}}
details.qa summary{{cursor:pointer;padding:13px 34px 13px 0;font-weight:650;position:relative;
  list-style:none;font-size:.97rem}}
details.qa summary::-webkit-details-marker{{display:none}}
details.qa summary::after{{content:"+";position:absolute;right:6px;top:11px;font-size:1.24rem;
  color:var(--mute);font-weight:400;line-height:1}}
details.qa[open] summary::after{{content:"−"}}
details.qa summary:hover{{color:var(--fall)}}
details.qa summary:focus-visible{{outline:2px solid var(--navy);outline-offset:2px}}
.qa-a{{padding:0 30px 15px 0;color:var(--mute);font-size:.93rem;max-width:76ch}}
.qa-a p{{margin:0}}

/* 금지 목록 */
ul.never{{list-style:none;padding:0;margin:16px 0}}
ul.never li{{border-left:4px solid var(--g1);background:#F7F9FC;padding:13px 18px;margin:0 0 10px;
  border-radius:0 4px 4px 0}}
ul.never b{{display:block;font-weight:700;font-size:.97rem}}
ul.never span{{color:var(--mute);font-size:.89rem}}
ul.plain{{list-style:none;padding:0;margin:14px 0}}
ul.plain li{{padding:11px 0;border-bottom:1px solid var(--rule)}}
ul.plain b{{display:block;font-weight:700;font-size:.95rem}}
ul.plain span{{color:var(--mute);font-size:.88rem}}

.note{{border-left:4px solid var(--rise);background:#FCF4F3;padding:15px 20px;margin:20px 0;
  font-size:.95rem;max-width:74ch}}
.note b{{font-weight:750}}
.note.calm{{border-left-color:var(--g3);background:var(--panel)}}
footer{{margin:62px 0 44px;padding-top:18px;border-top:1px solid var(--rule);
  font-size:.82rem;color:var(--mute)}}
@media (max-width:820px){{
  body{{font-size:15px}} h1{{font-size:1.8rem}} h2{{font-size:1.26rem}}
  .calc-b,.bc-body{{grid-template-columns:1fr}}
  .bar .wrap{{gap:12px}} .bar .big{{font-size:1.1rem}} .bar .dt{{display:none}}
  .bar .sep{{display:none}}
  .ladder.wide{{display:none}} .ladder-v{{display:block}}
}}
@media print{{.bar{{position:static;background:#fff;color:#000}} .calc{{display:none}}
  details.qa{{display:block}} details.qa .qa-a{{display:block}} h2{{page-break-after:avoid}}}}
@media (prefers-reduced-motion:reduce){{html{{scroll-behavior:auto}}}}
</style></head><body>

<div class="bar"><div class="wrap">
  <div class="act {ACTK}"><div class="lab">오늘의 판정</div><div class="big">{ACT}</div></div>
  <div class="sep"></div>
  <div><div class="lab">유지 비중</div><div class="big">{round(NOW['expo']*100)}%</div></div>
  <div><div class="lab">모델 목표</div><div class="big">{NOW['target']*100:.1f}%</div></div>
  <div><div class="lab">밴드</div><div class="big">{CUR['name']}</div></div>
  <div class="dt">{NOW['date']} 종가 · 코스피 {NOW['kospi']:,.0f} · 백분위 {pct(NOW['pctl'])}</div>
</div></div>

<div class="wrap">
<header class="top">
  <h1>운영 가이드북</h1>
  <p class="lede">시장폭 익스포저 모델을 실제로 굴리기 위한 지침입니다.
  밴드를 확인하고, 차이가 15%p 이상일 때만 움직이며, 그 외에는 아무것도 하지 않습니다.</p>
  {ladder(CUR['lo'])}
  <p class="dim" style="font-size:.85rem;margin-top:2px">
  표시된 위치가 {NOW['date']} 기준입니다. 밴드를 누르면 해당 지침으로 이동합니다.</p>
  <div class="note calm" style="margin-top:22px"><b>오늘은 아무것도 하지 않는 날입니다.</b>
  {ACTD} 모델 목표가 {TGT['name']} 밴드로 내려갔지만 아직 실행 기준을 넘지 않았으므로,
  {CUR['name']} 밴드의 비중 {round(NOW['expo']*100)}%를 그대로 유지하고 기록만 남깁니다.
  다음 주에 목표가 조금 더 내려가면 그때 실행하게 됩니다.</div>
</header>

<div class="calc">
  <div class="calc-h">오늘 실행할지 판정하기</div>
  <div class="calc-b">
    <div class="fld">
      <label for="p">모델이 산출한 목표 비중 (%)</label>
      <input id="p" type="number" min="0" max="100" step="0.5" value="{round(NOW['target']*100)}">
      <div class="hint">주간 계산 2단계에서 나오는 값입니다. 12.5% 단위입니다.</div>
    </div>
    <div class="fld">
      <label for="c">현재 주식 비중 (%)</label>
      <input id="c" type="number" min="0" max="100" step="0.5" value="{round(NOW['expo']*100)}">
      <div class="hint">현금을 포함한 총자산 대비 비율입니다.</div>
    </div>
    <div id="verdict"><div class="vt">—</div><div class="vd">값을 입력하시면 판정이 표시됩니다.</div></div>
  </div>
</div>

<h2><span class="no">1</span>매주 하는 일</h2>
<div class="prose"><p>주 1회, 같은 요일 장 마감 후에 수행합니다. 평활을 거친 신호이므로 매일 볼 필요가 없습니다.</p></div>
<ol class="steps">
  <li><b>시장폭을 계산합니다</b><span>코스피 시가총액 상위 250종목 중 자기 200일 이동평균을 웃도는 종목의 비율을 구합니다.</span></li>
  <li><b>백분위로 환산하고 평균을 냅니다</b><span>최근 3년 분포에서 백분위를 구한 뒤, 이동평균 150·200·250일과 평활 20·40·60일을 교차한 9개 구성의 값을 평균합니다. 8분의 1 단위로 반올림한 값이 목표 비중입니다.</span></li>
  <li><b>차이를 확인합니다</b><span>목표 비중과 현재 비중의 차이가 15%p 미만이면 아무것도 하지 않고 기록만 남깁니다.</span></li>
  <li><b>15%p 이상일 때만 실행합니다</b><span>줄일 때는 당일 또는 익일에 한 번에, 늘릴 때는 3영업일 간격으로 3회에 나누어 실행합니다.</span></li>
</ol>
<div class="note calm">줄일 때와 늘릴 때의 속도가 다른 이유는 실패 비용이 다르기 때문입니다.
늦게 줄이면 손실이 확정되지만, 늦게 늘리면 수익 일부를 놓치는 데 그칩니다.
검증 기간 연평균 실행 횟수는 {OPS['per_year_mean']:.1f}회였고, 한 해에 한 번도 실행하지 않은 해도 {OPS['zero_years']}개년 있었습니다.</div>

<h2><span class="no">2</span>밴드별 지침</h2>
<div class="prose"><p>현재 밴드를 찾아 해당 항목만 보시면 됩니다.
오른쪽 표는 검증 기간에 그 밴드에서 실제로 관측된 값입니다.</p></div>
{band_cards}

<h2><span class="no">3</span>이 신호가 알려 주는 것과 알려 주지 않는 것</h2>
<div class="prose">
<p>가장 자주 오해하는 지점입니다. 시장폭 백분위는 <b>지수가 오를지 내릴지를 예측하지 않습니다.</b>
최대 방어 밴드에서도 3개월 뒤 지수는 중앙값 {pct(B[0]['m3_med'],1,True)}로 올랐고 상승 확률은 {pct(B[0]['m3_win'],0)}였습니다.</p>
<p>이 지표가 실제로 구분하는 것은 <b>결과가 흩어지는 정도</b>입니다.
최대 방어 밴드의 연환산 변동성은 {pct(B[0]['ann_vol'])}로 중립 밴드({pct(B[2]['ann_vol'])})보다 훨씬 높고,
3개월 안에 10% 넘게 하락할 확률이 {pct(B[0]['p_drop10'],0)}에 이르며 최악 사례는 {pct(B[0]['worst3'])}였습니다.
같은 기대수익이라도 실패했을 때의 폭이 다르기 때문에 비중을 줄이는 것입니다.</p>
<p>따라서 밴드가 낮을 때 지수가 오르더라도 신호가 틀린 것이 아닙니다.
확률이 나쁜 구간에서 좋은 결과가 나온 것뿐이며, 같은 확률에 반복해서 노출되면 결국 큰 손실을 만나게 됩니다.</p>
</div>

<h2><span class="no">4</span>이 전략이 지는 때</h2>
<div class="prose">
<p>운용을 중단하게 만드는 가장 큰 압력은 손실이 아니라 <b>남들보다 뒤처진다는 감각</b>입니다.
검증 기간에서 12개월 기준으로 단순 보유에 뒤처진 경우가 {pct(RQ['lag_prob'],0)}였습니다. 뒤처지는 것이 기본 상태입니다.</p>
</div>
<div class="scroll"><table class="g">
<thead><tr><th>지수 12개월 수익률 구간</th><th>해당 기간 비중</th><th>모델 상대성과 중앙값</th><th>모델 우위 확률</th></tr></thead>
<tbody>{regime_rows}</tbody></table></div>
<div class="note"><b>이 표가 이 문서에서 가장 중요합니다.</b>
지수가 12개월간 20% 넘게 하락한 국면에서 모델은 100% 우위였고 중앙값 {pct(REG[0]['med'],1,True)}를 기록했습니다.
반대로 지수가 30% 넘게 오른 국면에서는 {pct(REG[-1]['win'],0)}만 우위였습니다.
모든 성과가 전체 기간의 {pct(REG[0]['share']+REG[1]['share'],0)}에 해당하는 하락 국면에 집중되어 있습니다.
강세장에서 답답하다는 이유로 규칙을 버리면, 그 대가를 치를 하락 국면만 남기고 보상은 포기하는 결과가 됩니다.</div>

<h2><span class="no">5</span>상황별 판단</h2>
<div class="prose"><p>해당하는 항목을 눌러 확인하시기 바랍니다.</p></div>
{qa_items}

<h2><span class="no">6</span>절대 하지 않는 다섯 가지</h2>
<ul class="never">{never_items}</ul>

<h2><span class="no">7</span>언제 이 전략을 폐기해야 하는가</h2>
<div class="prose"><p>아래 조건 중 하나라도 성립하면 운용을 멈추고 재검증하셔야 합니다.
검증 기간에 관측된 최악값을 기준으로 여유를 두어 설정했습니다.</p></div>
<div class="scroll"><table class="g">
<thead><tr><th>구분</th><th>조건</th><th>근거</th></tr></thead>
<tbody>{trip_rows}</tbody></table></div>
<h3>반대로, 폐기 사유가 아닌 것</h3>
<ul class="plain">{nottrip_items}</ul>

<h2><span class="no">8</span>점검 주기</h2>
<div class="scroll"><table class="g">
<thead><tr><th>주기</th><th style="text-align:left">할 일</th></tr></thead>
<tbody>
<tr><td class="nm">주 1회</td><td class="cond">시장폭 백분위 계산 → 목표 비중 산출 → 15%p 판정 → 실행 또는 보류 기록</td></tr>
<tr><td class="nm">월 1회</td><td class="cond">보유 종목의 -8% 손절선 점검, 상대강도 순위 갱신</td></tr>
<tr><td class="nm">분기 1회</td><td class="cond">유니버스 갱신(시가총액 상위 250종목 재선정), 상장폐지·신규상장 반영</td></tr>
<tr><td class="nm">연 1회</td><td class="cond">7번 항목의 폐기 조건 네 가지를 모두 점검, 연간 실행 횟수 확인</td></tr>
</tbody></table></div>

<h2><span class="no">9</span>구현 검증값</h2>
<div class="prose"><p>직접 구현하신 코드가 아래 값을 재현하면 계산이 일치하는 것입니다.
하나라도 어긋나면 유니버스 구성이나 백분위 창 길이를 먼저 확인해 보시기 바랍니다.</p></div>
<div class="scroll"><table class="g">
<thead><tr><th>날짜</th><th>코스피</th><th>200일선 상회 비율</th><th>3년 백분위</th><th>목표 비중</th></tr></thead>
<tbody>{check_rows}</tbody></table></div>

<footer>
검증 근거: 2004-01-02 ~ {NOW['date']}, 코스피 5,597거래일.
CAGR 9.26%, 최대낙폭 -19.89%, Sharpe 0.52, 총 재조정 {OPS['total_trades']}회.
거래비용 매도 0.17% / 매수 0.02% 반영.<br>
본 문서는 정량 분석에 근거한 운영 지침이며 투자자문이 아닙니다. 투자 판단과 그 결과는 투자자 본인에게 귀속됩니다.
</footer>
</div>

<script>
(function(){{
  var BANDS = {BANDS_JS};
  var p = document.getElementById('p'), c = document.getElementById('c'), v = document.getElementById('verdict');
  function band(x){{ for (var i=0;i<BANDS.length;i++){{ if (x>=BANDS[i].lo && x<BANDS[i].hi) return BANDS[i]; }} return BANDS[BANDS.length-1]; }}
  function calc(){{
    var pv = parseFloat(p.value), cv = parseFloat(c.value);
    if (isNaN(pv) || isNaN(cv)) return;
    pv = Math.min(100, Math.max(0, pv)); cv = Math.min(100, Math.max(0, cv));
    var tgt = Math.round(pv/100*8)/8, gap = tgt*100 - cv, b = band(tgt);
    if (Math.abs(tgt*100 - pv) > 0.01) p.value = (tgt*100).toFixed(1);
    var t, d, k;
    if (Math.abs(gap) < 15){{
      k = 'hold';
      t = '실행하지 않습니다';
      d = '목표 ' + (tgt*100).toFixed(1) + '% · 현재 ' + cv.toFixed(1) + '% · 차이 '
        + Math.abs(gap).toFixed(1) + '%p. 15%p 기준에 미달하므로 기록만 남기고 다음 주에 다시 확인합니다.';
    }} else if (gap < 0) {{
      k = 'cut';
      t = '비중을 ' + (tgt*100).toFixed(1) + '%로 줄입니다';
      d = '차이 ' + Math.abs(gap).toFixed(1) + '%p. 당일 또는 익일에 한 번에 실행하고, '
        + '상대강도 하위 종목부터 정리합니다. 반등을 기다리지 않습니다. 현재 밴드는 ' + b.name + '입니다.';
    }} else {{
      k = 'go';
      t = '비중을 ' + (tgt*100).toFixed(1) + '%로 늘립니다';
      d = '차이 ' + gap.toFixed(1) + '%p. 3영업일 간격으로 3회에 나누어 채웁니다. '
        + '도중에 신호가 되돌아가면 남은 계획을 취소합니다. 현재 밴드는 ' + b.name + '입니다.';
    }}
    v.className = k;
    v.querySelector('.vt').textContent = t;
    v.querySelector('.vd').textContent = d;
  }}
  p.addEventListener('input', calc); c.addEventListener('input', calc); calc();

  document.querySelectorAll('.ladder .seghit, .ladder-v .vb').forEach(function(el){{
    function go(){{
      var t = document.getElementById('band-' + el.getAttribute('data-cid'));
      if (t) t.scrollIntoView({{block:'start'}});
    }}
    el.addEventListener('click', go);
    el.addEventListener('keydown', function(e){{
      if (e.key === 'Enter' || e.key === ' ') {{ e.preventDefault(); go(); }}
    }});
  }});
}})();
</script>
</body></html>"""

open('/mnt/user-data/outputs/operating_guidebook.html', 'w').write(HTML)
print('생성 완료: %d bytes' % len(HTML))
