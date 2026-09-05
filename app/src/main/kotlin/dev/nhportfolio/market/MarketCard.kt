package dev.nhportfolio.market

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.nhportfolio.ui.bpPct
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** 마지막 계산 후 이만큼 지나면 갱신을 권한다. 주 1회 신호이므로 그 전에는 아무 말도 하지 않는다. */
private const val STALE_DAYS = 7

/**
 * 판정 문구를 만든다. [today] 는 화면이 한 번 계산해 넘긴 오늘 날짜 — 이 함수 자체는 시계를 보지 않는다.
 *
 * 실행 방식 문구까지 한 함수에서 만든다. 갈래가 세 가지(HOLD/CUT/ADD)뿐이라 템플릿 엔진 없이
 * 문자열 이어붙이기로 충분하다.
 */
internal fun verdictText(
    signal: Signal,
    verdict: Verdict,
    fromCashTarget: Boolean,
    today: LocalDate,
): Pair<String, String> {
    val held = signal.targetBp - verdict.gapBp
    val gapAbs = abs(verdict.gapBp)
    val title =
        when (verdict.action) {
            Action.HOLD -> "실행하지 않습니다"
            Action.CUT -> "비중을 ${signal.targetBp.bpPct()}로 줄입니다"
            Action.ADD -> "비중을 ${signal.targetBp.bpPct()}로 늘립니다"
        }
    val how =
        when (verdict.action) {
            Action.HOLD -> "15%p 기준에 미달하므로 기록만 남깁니다."
            Action.CUT -> "당일 또는 익일에 한 번에 실행합니다."
            Action.ADD -> "3영업일 간격으로 3회에 나누어 채웁니다."
        }
    val detail = StringBuilder("목표 ${signal.targetBp.bpPct()} · 유지 ${held.bpPct()} · 차이 ${gapAbs.bpPct()}p. $how")
    // 예수금 목표가 없어 실제 비중으로 대신했다는 사실은 판정의 근거가 달라졌다는 뜻이라 반드시 알려야 한다.
    if (!fromCashTarget) detail.append(" (적용한 목표가 없어 실제 비중과 비교합니다)")
    if (signal.window < Breadth.PCT_WIN) detail.append(" 백분위 창 ${signal.window}/${Breadth.PCT_WIN}일.")
    val asOfDate = LocalDate.parse(signal.asOf, DateTimeFormatter.BASIC_ISO_DATE)
    val daysSince = ChronoUnit.DAYS.between(asOfDate, today)
    detail.append(
        if (daysSince >= STALE_DAYS) " 기준 $asOfDate, ${daysSince}일 경과 — 갱신을 권합니다." else " 기준 $asOfDate.",
    )
    return title to detail.toString()
}

private fun Band.label(): String =
    when (this) {
        Band.MAX_DEFENSE -> "최대 방어"
        Band.DEFENSE -> "방어"
        Band.NEUTRAL -> "중립"
        Band.ACTIVE -> "적극"
        Band.MAX_INVEST -> "최대 투입"
    }

/**
 * 시장 신호 카드. [heldBp] 는 (유지 비중, 예수금 목표 기준 여부) — [dev.nhportfolio.portfolio.heldBp] 가 만든다.
 *
 * 캐시가 모자라 [signal] 이 null 이면 판정 대신 확보한 거래일 수([signalDays])를 보여준다.
 * 카드를 누르면(버튼 제외) [onGuide] 로 밴드 지침 화면을 연다(Task 5 에서 실제로 연결한다).
 */
@Composable
internal fun MarketCard(
    signal: Signal?,
    signalDays: Int,
    heldBp: Pair<Int, Boolean>,
    sync: SyncState,
    marketError: String?,
    today: LocalDate,
    onSync: () -> Unit,
    onApply: (Int) -> Unit,
    onGuide: () -> Unit,
) {
    // 최초 백필은 약 30MB 다운로드다 — 증분 갱신과 달리 매번 확인을 받는다.
    var confirmBackfill by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "밴드 지침", onClick = onGuide)
            .padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 13.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("시장 신호", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(
                enabled = sync !is SyncState.Running,
                onClick = { if (signalDays == 0) confirmBackfill = true else onSync() },
            ) { Text("갱신") }
        }

        marketError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        if (sync is SyncState.Running) {
            LinearProgressIndicator(
                progress = { sync.done.toFloat() / sync.total.coerceAtLeast(1) },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${sync.done}/${sync.total} 종목", style = MaterialTheme.typography.bodySmall)
        }

        if (signal == null) {
            Text("아직 계산할 수 없습니다 — 거래일 $signalDays, 최소 약 500일", style = MaterialTheme.typography.bodyMedium)
        } else {
            val (heldValue, fromCashTarget) = heldBp
            val verdict = Breadth.verdict(signal.targetBp, heldValue)
            val (title, detail) = verdictText(signal, verdict, fromCashTarget, today)
            val titleColor =
                when (verdict.action) {
                    Action.HOLD -> MaterialTheme.colorScheme.onSurface
                    Action.CUT -> MaterialTheme.colorScheme.error
                    Action.ADD -> MaterialTheme.colorScheme.primary
                }
            Text(title, style = MaterialTheme.typography.titleMedium, color = titleColor)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MarketStat("모델 목표", signal.targetBp.bpPct())
                MarketStat("유지 비중", heldValue.bpPct())
                MarketStat("밴드", signal.band.label())
            }
            TextButton(onClick = { onApply(signal.targetBp) }) { Text("이 목표로 맞추기") }
        }

        if (sync is SyncState.Done && sync.failed > 0) {
            Text("${sync.failed}종목 실패", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirmBackfill) {
        AlertDialog(
            onDismissRequest = { confirmBackfill = false },
            title = { Text("시장 데이터 갱신") },
            text = { Text("약 30 MB 를 내려받습니다. Wi-Fi 를 권합니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmBackfill = false
                        onSync()
                    },
                ) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBackfill = false }) { Text("취소") }
            },
        )
    }
}

/** 카드 안의 작은 수치 하나. 요약 카드의 StatBox 와 같은 라벨·값 타이포를 쓴다. */
@Composable
private fun MarketStat(
    label: String,
    value: String,
) {
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
