package dev.nhportfolio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import dev.nhportfolio.api.NhException
import dev.nhportfolio.security.VaultCorruptException
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

// 기기 로케일과 무관하게 한국 표기로 고정한다. DecimalFormat 은 스레드 안전하지 않으므로 매번 만든다
// (한 화면에 수십 개라 비용은 무시할 수준이다).
private fun formatter(pattern: String) = DecimalFormat(pattern, DecimalFormatSymbols(Locale.KOREA))

fun Long.krw(): String = formatter("#,##0").format(this)

fun Long.shares(): String = formatter("#,##0").format(this)

/** basis point -> 퍼센트 문자열. 1250 -> "12.50%" */
fun Int.bpPct(): String = formatter("#,##0.00").format(this / 100.0) + "%"

/** 수익률. 부호를 항상 붙인다. */
fun Double.pct(): String = formatter("+#,##0.00;-#,##0.00").format(this) + "%"

/** 배경이 어두운 테마인지. 손익·칩 색을 배경에 맞춰 고르는 데 쓴다. */
@Composable
private fun onDark(): Boolean = MaterialTheme.colorScheme.surface.luminance() < 0.5f

/** 국내 관례: 이익 빨강, 손실 파랑. 배경에 따라 대비를 맞춘 값을 고른다. */
@Composable
fun plColor(value: Double): Color {
    val dark = onDark()
    return when {
        value > 0 -> if (dark) ProfitRedDark else ProfitRed
        value < 0 -> if (dark) LossBlueDark else LossBlue
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

/** 매수/매도 칩의 글자색과 배경색. 수익률과 색은 같아도 칩이라는 형태로 갈린다. */
data class ChipColors(
    val ink: Color,
    val surface: Color,
)

/**
 * [delta] 주식 수에 맞는 칩 색. 아무 일도 없는 상태(유지·계산 불가)에는 색을 쓰지 않는다 —
 * 색은 행동이 필요할 때만 쓴다.
 */
@Composable
fun deltaChipColors(delta: Long?): ChipColors {
    val dark = onDark()
    return when {
        delta == null || delta == 0L -> {
            ChipColors(MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceVariant)
        }

        delta > 0 -> {
            if (dark) ChipColors(BuyInkDark, BuySurfaceDark) else ChipColors(BuyInk, BuySurface)
        }

        else -> {
            if (dark) ChipColors(SellInkDark, SellSurfaceDark) else ChipColors(SellInk, SellSurface)
        }
    }
}

/** 비중 바의 트랙색과 채움색. 무채색이라 손익·행동 색과 겹치지 않는다. */
@Composable
fun barColors(): ChipColors = if (onDark()) ChipColors(BarFillDark, BarTrackDark) else ChipColors(BarFill, BarTrack)

/**
 * 예외 -> 사용자 문구. 비밀이 담길 수 있는 원본 메시지는 절대 그대로 쓰지 않는다
 * (업무 오류 `rsp_msg` 만 예외 — NH 가 준 사용자용 문구다).
 *
 * [VaultCorruptException] 은 [IllegalStateException] 의 하위 타입이라 반드시 먼저 검사한다.
 */
fun Throwable.userMessage(): String =
    when (this) {
        is NhException -> {
            when (code) {
                "AUTH", "HTTP400", "HTTP401" -> "인증 실패 — 설정에서 앱 키를 확인하세요"

                "HTTP429" -> "요청이 많습니다. 잠시 후 다시 시도하세요"

                "WS" -> "실시간 연결이 끊겼습니다"

                // rsp_msg 가 없거나 빈 문자열이면(coerceInputValues 기본값) message 는 null 이
                // 아니라 "" 라서 `?:` 가 발동하지 않는다 — 빈 화면 대신 안내 문구를 낸다.
                else -> message?.takeIf { it.isNotBlank() } ?: "오류가 발생했습니다"
            }
        }

        is VaultCorruptException -> {
            "저장된 데이터가 손상되었습니다"
        }

        is IOException -> {
            "네트워크 오류"
        }

        is SerializationException -> {
            "응답 형식 오류"
        }

        // 잠김 — 게이트가 처리하므로 화면에 아무것도 띄우지 않는다
        is IllegalStateException -> {
            ""
        }

        else -> {
            "오류가 발생했습니다"
        }
    }

/** 계좌 구성 파이의 조각 색과, 그에 맞는 라벨 글자색. */
data class CompositionColors(
    val stock: Color,
    val cash: Color,
    val stockInk: Color,
    val cashInk: Color,
)

@Composable
fun compositionColors(): CompositionColors =
    if (onDark()) {
        CompositionColors(StockIndigoDark, CashAmberDark, StockIndigoDark, CashAmberDark)
    } else {
        CompositionColors(StockIndigo, CashAmber, StockIndigoInk, CashAmberInk)
    }
