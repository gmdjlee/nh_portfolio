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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
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
private val Surface2Dark = Color(0xFF1C222B)
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

/**
 * 계좌 구성 파이 — 주식 인디고, 현금 앰버.
 *
 * 손익(빨강·파랑)과 조작 요소(Moss 초록)가 쓰는 색은 피했다 — 한 행 안에서 뜻이 섞이면 안 된다.
 * 파이 색을 그대로 작은 글자에 쓰면 앰버가 흰 바탕에서 2.2:1 로 못 읽으므로 라벨은 어두운 값을
 * 따로 둔다(#4A44B8 7.5:1, #8A6410 5.4:1).
 */
internal val StockIndigo = Color(0xFF5B54D6)
internal val CashAmber = Color(0xFFE0A32E)
internal val StockIndigoInk = Color(0xFF4A44B8)
internal val CashAmberInk = Color(0xFF8A6410)
internal val StockIndigoDark = Color(0xFF8B84FF)
internal val CashAmberDark = Color(0xFFF0B94E)

/** 비중 바 — 채운 막대는 무채색으로 둔다. 색은 행동(칩)에만 쓴다. */
internal val BarTrack = Color(0xFFEDEFF2)
internal val BarFill = Color(0xFFA9B2BF)
internal val BarTrackDark = Color(0xFF232A34)
internal val BarFillDark = Color(0xFF5A6472)

/**
 * 카드 묶음 화면(계좌 목록·설정)의 바탕색·카드색·테두리색.
 *
 * 카드는 바탕보다 **밝아야** 떠 보인다. 다크에서 surface(#0E1116)를 카드로 쓰면 바탕이 될
 * surfaceVariant(#1C222B)보다 어두워져 카드가 오히려 파여 보인다 — 그래서 이 화면 전용으로
 * 바탕을 기본 배경보다 더 내리고 카드를 그 위로 올린 값을 따로 둔다.
 *
 * 대비는 계산값이다: 다크에서 흐린 글자(#8B93A1)가 카드 위에서 5.53:1 (AA 통과).
 */
internal val GroupedPage = Color(0xFFF4F6F8)
internal val GroupedCard = Color(0xFFFFFFFF)
internal val GroupedLine = Color(0xFFEDEFF2)
internal val GroupedPageDark = Color(0xFF080B0F)
internal val GroupedCardDark = Color(0xFF171C24)
internal val GroupedLineDark = Color(0xFF262E39)

/**
 * 종목 행 명세(잔고·평균·현재)의 라벨색과 숫자색.
 *
 * 라벨은 흐리게, 숫자는 진하게 갈라 둔다 — 한 줄에 여섯 조각이 들어가도 숫자가 먼저 읽힌다.
 * onSurfaceVariant 하나로 둘을 다 칠하면 라벨과 숫자가 같은 무게로 읽혀 줄이 뭉갠다.
 */
internal val DetailLabel = Color(0xFF8A9099)
internal val DetailValue = Color(0xFF3E4550)
internal val DetailLabelDark = Color(0xFF7C838F)
internal val DetailValueDark = Color(0xFFC9CFD8)

/**
 * 신용/융자 배지 — 빌린 돈으로 산 줄이라는 표시.
 *
 * 같은 빨강 계열이 이미 셋(상승 [ProfitRed], 매수 [BuyInk], 오류 [Ember]) 있는데 전부
 * 주황빛 빨강이다. 배지는 그보다 차고 깊은 진홍으로 갈라 둔다 — 이름 바로 뒤라는 자리와
 * 11sp 라는 크기까지 겹치지 않아 한 행에서 셋을 동시에 봐도 서로 섞이지 않는다.
 *
 * 대비는 계산값이다: 밝은 배경 6.02:1, 어두운 배경 6.95:1 (각자의 칩 배경 대비, AA 통과).
 */
internal val CreditInk = Color(0xFFA6273F)
internal val CreditSurface = Color(0xFFFBE9EC)
internal val CreditInkDark = Color(0xFFEE8D9C)
internal val CreditSurfaceDark = Color(0xFF2E1A1F)

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
                    fontSize = 34.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-1.2).sp,
                    fontFeatureSettings = "tnum",
                ),
            // 종목 한 줄의 평가금액. 이름(titleMedium 16sp)보다 크게 잡아 돈이 먼저 읽히게 한다.
            titleLarge =
                base.titleLarge.copy(
                    fontSize = 19.sp,
                    lineHeight = 24.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = (-0.5).sp,
                    fontFeatureSettings = "tnum",
                ),
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

/**
 * 이 폭부터 펼친 화면으로 본다. 실측(SM-F976N, 밀도 420): 접힘 411dp, 펼침 859dp —
 * 둘 사이 어디를 잡아도 되지만 600dp 는 Material 이 쓰는 값이라 다른 기기에서도 말이 된다.
 */
private const val EXPANDED_WIDTH_DP = 600

/** 펼친 화면의 타이포 배율. 폭은 2.09배지만 글자를 그만큼 키우면 우스워진다 — 한 단계 큰 글씨. */
private const val EXPANDED_TYPE_SCALE = 1.2f

private fun TextStyle.scaled(factor: Float): TextStyle =
    copy(
        fontSize = fontSize * factor,
        lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight,
        letterSpacing = if (letterSpacing.isSpecified) letterSpacing * factor else letterSpacing,
    )

/**
 * 앱이 실제로 쓰는 슬롯만 키운다. 화면 코드는 스타일 이름으로만 글자를 고르므로,
 * 여기서 한 번 곱해 두면 모든 화면이 따라온다.
 */
private fun Typography.scaled(factor: Float): Typography =
    if (factor == 1f) {
        this
    } else {
        copy(
            displaySmall = displaySmall.scaled(factor),
            titleLarge = titleLarge.scaled(factor),
            titleMedium = titleMedium.scaled(factor),
            titleSmall = titleSmall.scaled(factor),
            bodyLarge = bodyLarge.scaled(factor),
            bodyMedium = bodyMedium.scaled(factor),
            bodySmall = bodySmall.scaled(factor),
            labelLarge = labelLarge.scaled(factor),
            labelMedium = labelMedium.scaled(factor),
            labelSmall = labelSmall.scaled(factor),
        )
    }

/**
 * 폴더블을 펼치면 글자가 커진다. 여백과 터치 대상은 그대로 둔다 — 폭이 넓어졌다고
 * 44dp 대상이 커져야 할 이유가 없고, 커지면 오히려 성기게 보인다.
 *
 * 접었다 펴면 액티비티가 통째로 다시 만들어지므로(configChanges 를 걸지 않았다) 전환은
 * 저절로 처리된다. 폰트 배율(설정의 글자 크기)은 sp 가 이미 반영하므로 여기서 곱하지 않는다.
 */
@Composable
fun NhTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val expanded = LocalConfiguration.current.screenWidthDp >= EXPANDED_WIDTH_DP
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        shapes = DataShapes,
        typography = NhTypography.scaled(if (expanded) EXPANDED_TYPE_SCALE else 1f),
        content = content,
    )
}

/** 비중 막대 폭. 막대는 글자와 함께 읽는 눈금이라 글자가 커지면 같이 길어져야 한다. */
@Composable
fun weightBarWidth(selecting: Boolean): Dp =
    when {
        LocalConfiguration.current.screenWidthDp >= EXPANDED_WIDTH_DP -> 220.dp

        // 선택 모드에서는 체크박스가 앞자리를 가져가므로 그만큼 줄인다.
        selecting -> 128.dp

        else -> 150.dp
    }

/**
 * 화면 테마 선택. 저장은 [name] 문자열로 하므로 **상수 이름을 바꾸면 저장된 설정이 초기화된다**
 * — 모르는 이름은 [from] 이 [AUTO] 로 흡수한다.
 */
enum class ThemeMode(
    val label: String,
) {
    AUTO("자동"),
    LIGHT("밝게"),
    DARK("어둡게"),
    ;

    companion object {
        /** 저장값이 없거나 모르는 값이어도 화면이 죽지 않는다 — 지정이 없는 것으로 본다. */
        fun from(name: String?): ThemeMode = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/** [ThemeMode.AUTO] 일 때만 시스템 설정을 따른다. */
@Composable
fun ThemeMode.isDark(): Boolean =
    when (this) {
        ThemeMode.AUTO -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
