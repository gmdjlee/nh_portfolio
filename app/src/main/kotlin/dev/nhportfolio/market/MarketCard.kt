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
import kotlin.math.ceil

/** 마지막 계산 후 이만큼 지나면 갱신을 권한다. 주 1회 신호이므로 그 전에는 아무 말도 하지 않는다. */
private const val STALE_DAYS = 7

/** ETA 를 걸기 시작하는 최소 표본. 그 전에는 초반 몇 종목의 속도로 전체를 추정한 오차가 너무 크다. */
private const val ETA_MIN_DONE = 5
private const val MIN_MS = 60_000.0

/**
 * 갱신 중 카드에 보여줄 문구. [done] 이 [ETA_MIN_DONE] 미만이면 진행 개수만 보여준다.
 * 그 이상이면 ETA = 경과 / done × 남은 종목이고, 1분 미만이면 "1분 미만" 으로, 그 이상이면
 * 분 단위로 올림해 보여준다 — 반올림하면 "약 0분 남음" 처럼 다 됐다는 착각을 줄 수 있다.
 */
internal fun syncProgressText(
    sync: SyncState.Running,
    startedAt: Long,
    now: Long,
): String {
    val base = "갱신 중 ${sync.done}/${sync.total} 종목"
    if (sync.done < ETA_MIN_DONE) return base
    val elapsed = (now - startedAt).coerceAtLeast(0).toDouble()
    val remaining = sync.total - sync.done
    val etaMs = elapsed / sync.done * remaining
    val eta = if (etaMs < MIN_MS) "1분 미만" else "약 ${ceil(etaMs / MIN_MS).toInt()}분 남음"
    return "$base · $eta"
}

/**
 * 갱신이 끝난 뒤 보여줄 문구. [hasSignal] 이 false(캐시가 아직 모자람)면 "거래일 N" 을 붙이지
 * 않는다 — [SignalOrPlaceholder] 의 "아직 계산할 수 없습니다 — 거래일 N…" 줄이 이미 N 을
 * 보여주므로, 여기서 또 붙이면 같은 숫자가 두 줄에 겹친다.
 */
internal fun syncDoneText(
    hasSignal: Boolean,
    signalDays: Int,
): String = if (hasSignal) "갱신 완료 · 거래일 $signalDays" else "갱신 완료"

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

/**
 * 시장 신호 카드. [heldBp] 는 (유지 비중, 예수금 목표 기준 여부) — [dev.nhportfolio.portfolio.heldBp] 가 만든다.
 * [actualBp] 는 실제 주식 비중 합(현금 제외, bp) — 계산은 화면(Rebalance)이 하고 카드는 표시만 한다.
 *
 * 캐시가 모자라 [signal] 이 null 이면 판정 대신 확보한 거래일 수([signalDays])를 보여준다.
 * 카드를 누르면(버튼 제외) [onGuide] 로 밴드 지침 화면을 연다 — 그 시점의 밴드([Signal.band])를
 * 같이 넘겨 지침 화면이 해당 밴드를 강조해 보여줄 수 있게 한다.
 */
@Composable
internal fun MarketCard(
    signal: Signal?,
    signalDays: Int,
    heldBp: Pair<Int, Boolean>,
    actualBp: Int,
    sync: SyncState,
    syncStartedAt: Long,
    marketError: String?,
    today: LocalDate,
    onSync: () -> Unit,
    onApply: (Int) -> Unit,
    onGuide: (Band?) -> Unit,
) {
    // 최초 백필은 약 65MB 다운로드다 — 증분 갱신과 달리 매번 확인을 받는다.
    var confirmBackfill by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "밴드 지침", onClick = { onGuide(signal?.band) })
            .padding(start = 16.dp, end = 16.dp, top = 13.dp, bottom = 13.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("시장 신호", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(
                enabled = sync !is SyncState.Running,
                onClick = { if (signalDays == 0) confirmBackfill = true else onSync() },
            ) { Text(if (sync is SyncState.Running) "갱신 중…" else "갱신") }
        }

        marketError?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }

        SyncStatusLine(sync, syncStartedAt, signalDays, hasSignal = signal != null)
        SignalOrPlaceholder(signal, signalDays, sync, heldBp, actualBp, today, onApply)

        if (sync is SyncState.Done && sync.failed > 0) {
            Text("${sync.failed}종목 실패", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }

    if (confirmBackfill) {
        AlertDialog(
            onDismissRequest = { confirmBackfill = false },
            title = { Text("시장 데이터 갱신") },
            text = { Text("약 65 MB 를 내려받습니다(약 10분). Wi-Fi 를 권합니다.") },
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

/** 진행률 줄. Running 이면 진행 표시줄과 문구를, Done 이면 완료 문구를 보여준다. Idle 이면 아무것도 안 그린다. */
@Composable
private fun SyncStatusLine(
    sync: SyncState,
    syncStartedAt: Long,
    signalDays: Int,
    hasSignal: Boolean,
) {
    if (sync is SyncState.Running) {
        LinearProgressIndicator(
            progress = { sync.done.toFloat() / sync.total.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(syncProgressText(sync, syncStartedAt, System.currentTimeMillis()), style = MaterialTheme.typography.bodySmall)
    } else if (sync is SyncState.Done) {
        Text(syncDoneText(hasSignal, signalDays), style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * [signal] 이 없으면 갱신 중이 아닐 때만 안내 문구를 보여준다(갱신 중에는 [SyncStatusLine] 이 그 자리를
 * 대신한다 — 둘 다 보이면 같은 뜻을 두 번 말하는 꼴이다). 있으면 판정 제목·상세·수치·"이 목표로
 * 맞추기" 버튼까지 보여준다.
 */
@Composable
private fun SignalOrPlaceholder(
    signal: Signal?,
    signalDays: Int,
    sync: SyncState,
    heldBp: Pair<Int, Boolean>,
    actualBp: Int,
    today: LocalDate,
    onApply: (Int) -> Unit,
) {
    if (signal == null) {
        if (sync !is SyncState.Running) {
            Text("아직 계산할 수 없습니다 — 거래일 $signalDays, 최소 약 500일", style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
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
        // fromCashTarget 이 false 면 유지 비중이 곧 실제 비중을 대신한 값이라 이 둘은
        // 같은 숫자를 보여준다 — 우연이 아니라 의도된 동작이다(사양 §8.1).
        MarketStat("실제 비중", actualBp.bpPct())
    }
    TextButton(onClick = { onApply(signal.targetBp) }) { Text("이 목표로 맞추기") }
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
