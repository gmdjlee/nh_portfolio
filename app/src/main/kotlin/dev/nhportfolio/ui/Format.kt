package dev.nhportfolio.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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

/** 국내 관례: 이익 빨강, 손실 파랑. */
@Composable
fun plColor(value: Double): Color =
    when {
        value > 0 -> ProfitRed
        value < 0 -> LossBlue
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

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
                else -> message ?: "오류가 발생했습니다"
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

        is IllegalStateException -> {
            ""
        }

        // 잠김 — 게이트가 처리하므로 화면에 아무것도 띄우지 않는다
        else -> {
            message ?: "오류가 발생했습니다"
        }
    }
