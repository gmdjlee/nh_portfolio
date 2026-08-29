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
import androidx.compose.ui.unit.dp

// organic 팔레트 — 자연 톤. dynamic color 는 쓰지 않는다(계좌 화면이 기기마다 달라지면 안 된다).
private val Moss = Color(0xFF3F6B4A)
private val Sage = Color(0xFFDDE8D6)
private val Fern = Color(0xFFA8C8A0)
private val Clay = Color(0xFF9C6B45)
private val Sand = Color(0xFFF6F1E7)
private val Bark = Color(0xFF2B2A26)
private val Ember = Color(0xFFB3412F)
private val MossDeep = Color(0xFF2F4A34)
private val SageDeep = Color(0xFF4A5A46)
private val BarkSoft = Color(0xFF3A3A34)
private val SandMuted = Color(0xFFC6C6BC)
private val EmberLight = Color(0xFFE0705F)

internal val ProfitRed = Color(0xFFD1453B)
internal val LossBlue = Color(0xFF2F6BD1)

private val LightScheme =
    lightColorScheme(
        primary = Moss,
        onPrimary = Sand,
        primaryContainer = Sage,
        onPrimaryContainer = Bark,
        secondary = Clay,
        onSecondary = Sand,
        background = Sand,
        onBackground = Bark,
        surface = Sand,
        onSurface = Bark,
        surfaceVariant = Sage,
        onSurfaceVariant = SageDeep,
        error = Ember,
        onError = Sand,
    )

private val DarkScheme =
    darkColorScheme(
        primary = Fern,
        onPrimary = Bark,
        primaryContainer = MossDeep,
        onPrimaryContainer = Sage,
        secondary = Clay,
        onSecondary = Bark,
        background = Bark,
        onBackground = Sand,
        surface = Bark,
        onSurface = Sand,
        surfaceVariant = BarkSoft,
        onSurfaceVariant = SandMuted,
        error = EmberLight,
        onError = Bark,
    )

/** 유기적인 곡선 — 기본 M3 보다 확실히 둥글다. */
private val OrganicShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(14.dp),
        medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = RoundedCornerShape(36.dp),
    )

// 숫자가 세로로 정렬되도록 tabular figures 를 켠다 — 표에서 자릿수가 흔들리지 않는다.
private val NhTypography =
    Typography().let { base ->
        base.copy(
            titleMedium = base.titleMedium.copy(fontFeatureSettings = "tnum"),
            bodyLarge = base.bodyLarge.copy(fontFeatureSettings = "tnum"),
            bodyMedium = base.bodyMedium.copy(fontFeatureSettings = "tnum"),
            bodySmall = base.bodySmall.copy(fontFeatureSettings = "tnum"),
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
        shapes = OrganicShapes,
        typography = NhTypography,
        content = content,
    )
}
