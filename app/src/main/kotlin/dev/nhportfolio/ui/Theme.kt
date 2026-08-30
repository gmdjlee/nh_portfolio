package dev.nhportfolio.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 데이터 우선 팔레트 — 흰 바탕에 가까운 무채색 위에서 숫자가 주인공이 된다.
// dynamic color 는 쓰지 않는다(계좌 화면이 기기마다 달라지면 안 된다).
private val Ink = Color(0xFF111214)
private val InkMuted = Color(0xFF6E7481)
private val Paper = Color(0xFFFFFFFF)
private val Surface2 = Color(0xFFF4F6F8)
private val Divider = Color(0xFFEDEFF2)

// 브랜드의 끈은 조작 요소에만 남긴다 — organic 팔레트의 Moss.
private val Moss = Color(0xFF3F6B4A)
private val MossDeep = Color(0xFF2F4A34)
private val Fern = Color(0xFF7DD8A0)

private val InkDark = Color(0xFFF2F4F7)
private val InkMutedDark = Color(0xFF8B93A1)
private val PaperDark = Color(0xFF0E1116)
private val Surface2Dark = Color(0xFF151A21)
private val DividerDark = Color(0xFF212832)

private val Ember = Color(0xFFB3412F)
private val EmberLight = Color(0xFFE0705F)

/**
 * 손익 색 — 국내 관례(이익 빨강, 손실 파랑). 대비는 계산해서 고른 값이다.
 *
 * 밝은 배경: [ProfitRed] 4.54:1, [LossBlue] 5.07:1 (흰색 대비, WCAG AA 통과).
 * 어두운 배경: [ProfitRedDark] 6.15:1, [LossBlueDark] 5.92:1 (#0E1116 대비).
 * 기존 크림 배경(#F6F1E7)에서는 [ProfitRed] 가 4.03:1 로 AA 에 미달했다 — 배경을 흰색으로
 * 옮기면서 해소됐다.
 */
internal val ProfitRed = Color(0xFFD1453B)
internal val LossBlue = Color(0xFF2F6BD1)
internal val ProfitRedDark = Color(0xFFFF5A4E)
internal val LossBlueDark = Color(0xFF4D8DFF)

/** 매수/매도 칩 — 손익과 같은 색이지만 채운 칩이라 형태로 갈린다. */
internal val BuyInk = Color(0xFFB5372C)
internal val BuySurface = Color(0xFFFBE9E7)
internal val SellInk = Color(0xFF255BB8)
internal val SellSurface = Color(0xFFE8EFFB)
internal val BuyInkDark = Color(0xFFFF8578)
internal val BuySurfaceDark = Color(0xFF2A1A18)
internal val SellInkDark = Color(0xFF7FAEFF)
internal val SellSurfaceDark = Color(0xFF16202E)

/** 비중 바 — 채운 막대는 무채색으로 둔다. 색은 행동(칩)에만 쓴다. */
internal val BarTrack = Color(0xFFEDEFF2)
internal val BarFill = Color(0xFFA9B2BF)
internal val BarTrackDark = Color(0xFF232A34)
internal val BarFillDark = Color(0xFF5A6472)

private val LightScheme =
    lightColorScheme(
        primary = Moss,
        onPrimary = Paper,
        primaryContainer = Surface2,
        onPrimaryContainer = Ink,
        secondary = MossDeep,
        onSecondary = Paper,
        background = Paper,
        onBackground = Ink,
        surface = Paper,
        onSurface = Ink,
        surfaceVariant = Surface2,
        onSurfaceVariant = InkMuted,
        outlineVariant = Divider,
        error = Ember,
        onError = Paper,
    )

private val DarkScheme =
    darkColorScheme(
        primary = Fern,
        onPrimary = PaperDark,
        primaryContainer = Surface2Dark,
        onPrimaryContainer = InkDark,
        secondary = Fern,
        onSecondary = PaperDark,
        background = PaperDark,
        onBackground = InkDark,
        surface = PaperDark,
        onSurface = InkDark,
        surfaceVariant = Surface2Dark,
        onSurfaceVariant = InkMutedDark,
        outlineVariant = DividerDark,
        error = EmberLight,
        onError = PaperDark,
    )

/** 표를 위해 좁힌 곡선 — 둥근 모서리는 데이터가 앉을 자리를 깎아먹는다. */
private val DataShapes =
    Shapes(
        extraSmall = RoundedCornerShape(7.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(22.dp),
    )

/**
 * 숫자는 tabular figures + 굵게 + 자간을 좁혀 덩어리로 읽히게 한다.
 * 자릿수가 세로로 정렬되므로 표에서 흔들리지 않는다.
 */
private val NhTypography =
    Typography().let { base ->
        base.copy(
            displaySmall =
                base.displaySmall.copy(
                    fontSize = 33.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.8).sp,
                    fontFeatureSettings = "tnum",
                ),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            titleMedium =
                base.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.4).sp,
                    fontFeatureSettings = "tnum",
                ),
            titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            bodyLarge = base.bodyLarge.copy(fontFeatureSettings = "tnum"),
            bodyMedium = base.bodyMedium.copy(fontFeatureSettings = "tnum"),
            bodySmall = base.bodySmall.copy(fontFeatureSettings = "tnum"),
            labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"),
            labelMedium = base.labelMedium.copy(fontFeatureSettings = "tnum"),
        )
    }

@Composable
fun NhTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        shapes = DataShapes,
        typography = NhTypography,
        content = content,
    )
}
