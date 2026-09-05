package dev.nhportfolio.market

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 밴드 하나의 정적 지침 한 벌. `docs/manage/operating_guidebook.html` 2절의 문구와 관측값을
 * 그대로 옮긴 상수다 — 이 수치는 2004-01-02~2026-09-04 백테스트의 산출물이라 앱이 가진 4년치
 * 데이터로는 재현할 수 없고, 그래서 화면이 다시 계산하지 않는다.
 */
private data class BandEntry(
    val band: Band,
    val range: String,
    val lead: String,
    val dos: List<String>,
    val donts: List<String>,
    val stay: String,
    val duration: String,
    val vol: String,
    val down10: String,
    val up10: String,
    val worst3m: String,
)

private val BANDS =
    listOf(
        BandEntry(
            band = Band.MAX_DEFENSE,
            range = "0–13%",
            lead =
                "내부가 무너진 상태입니다. 지수가 버티고 있어도 대형주 다수가 추세를 잃었습니다. " +
                    "이 밴드의 3개월 변동성은 30.7%로 가장 높고, 3개월 안에 10% 넘게 빠질 확률이 29%입니다.",
            dos =
                listOf(
                    "목표 비중까지 즉시 줄입니다. 반등을 기다리지 않습니다.",
                    "남길 종목은 상대강도 상위 1~2개로 한정합니다.",
                    "확보한 현금은 그대로 둡니다. 채권이나 대체자산으로 돌리지 않습니다.",
                ),
            donts =
                listOf(
                    "지수가 오른다는 이유로 비중을 늘리지 않습니다.",
                    "평균 단가를 낮추는 추가 매수를 하지 않습니다.",
                ),
            stay = "8.8%",
            duration = "44일 / 272일",
            vol = "30.7%",
            down10 = "29%",
            up10 = "19%",
            worst3m = "−42.3%",
        ),
        BandEntry(
            band = Band.DEFENSE,
            range = "13–44%",
            lead =
                "위험이 쌓이는 중입니다. 아직 붕괴는 아니지만 종목 간 편차가 벌어지고 있습니다. " +
                    "변동성 24.0%로 평시보다 높고, 여기서 더 나빠지면 최대 방어로 넘어갑니다.",
            dos =
                listOf(
                    "상대강도 하위 종목부터 정리합니다.",
                    "신규 종목 편입을 중단합니다.",
                    "보유 종목의 -8% 손절선을 다시 확인합니다.",
                ),
            donts =
                listOf(
                    "하락 종목을 더 사서 물타기하지 않습니다.",
                    "“곧 반등할 것”이라는 판단으로 매도를 미루지 않습니다.",
                ),
            stay = "21.0%",
            duration = "44일 / 273일",
            vol = "24.0%",
            down10 = "8%",
            up10 = "11%",
            worst3m = "−32.8%",
        ),
        BandEntry(
            band = Band.NEUTRAL,
            range = "44–56%",
            lead =
                "가장 흔한 평상 상태입니다. 전체 기간의 30.5%를 차지합니다. " +
                    "변동성 17.5%로 가장 낮고 3개월 내 10% 하락 확률도 7%에 그칩니다. 특별히 할 일이 없는 구간입니다.",
            dos =
                listOf(
                    "비중을 그대로 둡니다.",
                    "개별 종목 손절선만 관리합니다.",
                    "분기 유니버스 갱신 등 정기 작업을 처리하기 좋은 시기입니다.",
                ),
            donts =
                listOf(
                    "차이가 15%p 미만인데 재조정하지 않습니다.",
                    "지루하다는 이유로 종목을 교체하지 않습니다.",
                ),
            stay = "30.5%",
            duration = "36일 / 269일",
            vol = "17.5%",
            down10 = "7%",
            up10 = "10%",
            worst3m = "−23.0%",
        ),
        BandEntry(
            band = Band.ACTIVE,
            range = "56–81%",
            lead =
                "시장 내부가 건강합니다. 대형주 다수가 자기 추세 위에 있습니다. " +
                    "3개월 내 10% 하락 확률이 2%로 전 밴드 중 가장 낮습니다.",
            dos =
                listOf(
                    "목표 비중까지 3영업일 간격으로 3회에 나누어 채웁니다.",
                    "하락기에 상대강도를 지킨 종목과 신고가를 경신하는 종목을 우선합니다.",
                    "살 종목이 마땅치 않으면 지수 ETF로 채웁니다.",
                ),
            donts =
                listOf(
                    "한 번에 전액을 투입하지 않습니다.",
                    "상한 100%를 넘겨 신용이나 레버리지를 쓰지 않습니다.",
                ),
            stay = "16.0%",
            duration = "44일 / 158일",
            vol = "20.1%",
            down10 = "2%",
            up10 = "18%",
            worst3m = "−25.4%",
        ),
        BandEntry(
            band = Band.MAX_INVEST,
            range = "82–100%",
            lead =
                "회복 초기일 가능성이 높습니다. 변동성은 29.1%로 높지만 방향이 위쪽으로 치우쳐 있습니다. " +
                    "3개월 내 10% 상승 확률 44%, 하락 확률 4%입니다. 급락 직후에 주로 나타납니다.",
            dos =
                listOf(
                    "목표 비중까지 채웁니다. 이 구간은 평균 70일 지속되었습니다.",
                    "직전 하락기에 손실을 준 종목이 아니라 새로 부상하는 주도주를 담습니다.",
                    "레버리지는 사용하지 않습니다. 상한은 100%입니다.",
                ),
            donts =
                listOf(
                    "늦었다고 판단하여 건너뛰지 않습니다.",
                    "이 구간이 계속될 것으로 가정하고 계획을 세우지 않습니다.",
                ),
            stay = "9.2%",
            duration = "70일 / 213일",
            vol = "29.1%",
            down10 = "4%",
            up10 = "44%",
            worst3m = "−18.9%",
        ),
    )

/** 3절 "이 신호가 알려 주는 것과 알려 주지 않는 것"의 본문 세 문단. */
private val NOT_TELLING =
    listOf(
        "가장 자주 오해하는 지점입니다. 시장폭 백분위는 지수가 오를지 내릴지를 예측하지 않습니다. " +
            "최대 방어 밴드에서도 3개월 뒤 지수는 중앙값 +3.1%로 올랐고 상승 확률은 59%였습니다.",
        "이 지표가 실제로 구분하는 것은 결과가 흩어지는 정도입니다. 최대 방어 밴드의 연환산 변동성은 30.7%로 " +
            "중립 밴드(17.5%)보다 훨씬 높고, 3개월 안에 10% 넘게 하락할 확률이 29%에 이르며 최악 사례는 -42.3%였습니다. " +
            "같은 기대수익이라도 실패했을 때의 폭이 다르기 때문에 비중을 줄이는 것입니다.",
        "따라서 밴드가 낮을 때 지수가 오르더라도 신호가 틀린 것이 아닙니다. " +
            "확률이 나쁜 구간에서 좋은 결과가 나온 것뿐이며, 같은 확률에 반복해서 노출되면 결국 큰 손실을 만나게 됩니다.",
    )

private data class NeverItem(
    val title: String,
    val body: String,
)

/** 6절 "절대 하지 않는 다섯 가지". */
private val NEVER_ITEMS =
    listOf(
        NeverItem(
            "하락 중 평균 단가를 낮추는 추가 매수",
            "검증 과정에서 -20% 방어선이 무너지는 가장 흔한 경로였습니다. 밴드가 내려간 상태에서는 어떤 종목도 추가 매수하지 않습니다.",
        ),
        NeverItem(
            "손실을 빨리 만회하기 위한 레버리지 또는 신용 사용",
            "주식 비중 상한은 100%입니다. -20%를 회복하려면 +25%가 필요한데, 레버리지로 시도하면 두 번째 -20%가 -40%가 됩니다.",
        ),
        NeverItem(
            "신호가 낮은 구간에서 재량으로 비중을 늘리는 행위",
            "이 밴드는 3개월 내 10% 이상 하락 확률이 29%인 구간입니다. 확률이 낮다는 것이지 안전하다는 뜻이 아닙니다.",
        ),
        NeverItem(
            "차이가 15%p 미만인데 실행하는 재조정",
            "거래비용만 누적되고 성과는 개선되지 않습니다. 이 기준을 없애면 연 매매가 세 자릿수로 늘어납니다.",
        ),
        NeverItem(
            "밴드를 건너뛰어 0%와 100%를 한 번에 오가는 전환",
            "단계적 조정이 이 전략의 핵심입니다. 전량 청산은 회복 국면 참여 수단을 완전히 제거합니다.",
        ),
    )

// 검증 조건(코스피 상위 250)과 이 앱의 신호(KODEX 200 구성종목)가 다르다는 사실을 수치와 같은 자리에서 밝힌다.
private const val FOOTER_1 =
    "관측값 출처: 2004-01-02 ~ 2026-09-04 코스피 5,597거래일 백테스트. CAGR 9.26%, 최대낙폭 −19.89%, " +
        "Sharpe 0.52, 총 재조정 64회. 거래비용 매도 0.17% / 매수 0.02% 반영. " +
        "본 화면은 정량 분석에 근거한 운영 지침이며 투자자문이 아닙니다."
private const val FOOTER_2 =
    "위 수치는 코스피 시가총액 상위 250종목으로 검증된 값입니다. " +
        "이 앱의 신호는 KODEX 200 구성종목으로 계산하므로 검증 조건과 완전히 같지 않습니다."

/**
 * 밴드 지침 화면. 전부 정적 텍스트다. [current] 는 진입 시점의 밴드를 강조 표시하는 데만 쓰이며,
 * 알 수 없는 값(밴드가 없거나 다섯 밴드 이름과 안 맞음)이 와도 강조 없이 그대로 보여준다.
 */
@Composable
fun BandGuideScreen(
    current: Band?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("밴드별 운영 지침") },
                // 뒤로는 아이콘이 아니라 글자다 — 설정·계좌 화면과 같은 표기를 쓴다(ui/Icon.kt 참고).
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("뒤로") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(BANDS) { entry -> BandSection(entry, isCurrent = entry.band == current) }
            item { CollapsibleSection(title = "이 신호가 알려 주지 않는 것") { NotTellingBody() } }
            item { CollapsibleSection(title = "절대 하지 않는 다섯 가지") { NeverBody() } }
            item { FooterNote() }
        }
    }
}

/** 밴드 한 칸. [isCurrent] 면 테두리와 "현재" 칩으로 강조한다 — 여러 칸이 동시에 강조되지 않는다. */
@Composable
private fun BandSection(
    entry: BandEntry,
    isCurrent: Boolean,
) {
    val shape = MaterialTheme.shapes.medium
    val highlight = if (isCurrent) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier
    Column(
        Modifier.fillMaxWidth().then(highlight).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    entry.band.label(),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    "주식 비중 ${entry.range}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isCurrent) CurrentChip()
        }
        Text(entry.lead, style = MaterialTheme.typography.bodyMedium)
        BulletGroup("해야 할 일", entry.dos)
        BulletGroup("하지 말아야 할 일", entry.donts)
        StatsTable(entry)
    }
}

/** 진입 시점 밴드였음을 알리는 작은 칩. */
@Composable
private fun CurrentChip() {
    Text(
        "현재",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier =
            Modifier
                .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun BulletGroup(
    title: String,
    bullets: List<String>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        bullets.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}

/** 밴드별 관측값 여섯 줄. 전부 검증 기간의 산출물([BandEntry] 참고) — 화면이 계산하지 않는다. */
@Composable
private fun StatsTable(entry: BandEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        StatRow("전체 기간 중 체류", entry.stay)
        StatRow("한 번 들어가면", entry.duration)
        StatRow("연환산 변동성", entry.vol)
        StatRow("3개월 내 10% 하락", entry.down10)
        StatRow("3개월 내 10% 상승", entry.up10)
        StatRow("3개월 최악 사례", entry.worst3m)
    }
}

@Composable
private fun StatRow(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

/** 접이식 섹션. [title] 행을 누르면 [body] 를 펼치거나 접는다 — 애니메이션 없이 조건부 컴포지션만 쓴다. */
@Composable
private fun CollapsibleSection(
    title: String,
    body: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClickLabel = if (expanded) "접기" else "펼치기", onClick = { expanded = !expanded })
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(if (expanded) "−" else "+", style = MaterialTheme.typography.titleMedium)
        }
        if (expanded) {
            Column(Modifier.padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { body() }
        }
    }
}

@Composable
private fun NotTellingBody() {
    NOT_TELLING.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun NeverBody() {
    NEVER_ITEMS.forEach { entry ->
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(entry.body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 출처와 유니버스 차이 고지. 숫자를 보여 주면서 검증 조건을 감추지 않는다(과제 지침). */
@Composable
private fun FooterNote() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(FOOTER_1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(FOOTER_2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
