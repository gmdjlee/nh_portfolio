package dev.nhportfolio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// 아이콘은 직접 그린다 — material-icons 를 끌어오면 의존성이 늘고, 이모지는 크기·색이 안 맞는다.
// 전부 같은 규격: 24 그리드, 선 굵기 2.2, 둥근 끝.
private const val GRID = 24f
private const val STROKE = 2.2f
private val DEFAULT_SIZE = 20.dp

private fun strokeStyle(scale: Float) = Stroke(width = STROKE * scale, cap = StrokeCap.Round, join = StrokeJoin.Round)

/** 뒤로 — 왼쪽 꺾쇠. */
@Composable
fun BackIcon(
    modifier: Modifier = Modifier,
    size: Dp = DEFAULT_SIZE,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / GRID
        val path =
            Path().apply {
                moveTo(15f * s, 18f * s)
                lineTo(9f * s, 12f * s)
                lineTo(15f * s, 6f * s)
            }
        drawPath(path, tint, style = strokeStyle(s))
    }
}

/** 새로고침 — 열린 원호와 화살촉. */
@Composable
fun RefreshIcon(
    modifier: Modifier = Modifier,
    size: Dp = DEFAULT_SIZE,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / GRID
        val inset = 3.4f * s
        drawArc(
            color = tint,
            startAngle = -55f,
            sweepAngle = 300f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(this.size.width - inset * 2, this.size.height - inset * 2),
            style = strokeStyle(s),
        )
        // 원호가 시작하는 오른쪽 위에 화살촉을 얹어 회전 방향을 보여준다.
        val head =
            Path().apply {
                moveTo(21f * s, 4f * s)
                lineTo(21f * s, 9.6f * s)
                lineTo(15.4f * s, 9.6f * s)
            }
        drawPath(head, tint, style = strokeStyle(s))
    }
}

/** 이름 수정 — 연필. */
@Composable
fun PencilIcon(
    modifier: Modifier = Modifier,
    size: Dp = 17.dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / GRID
        val path =
            Path().apply {
                moveTo(12f * s, 20f * s)
                lineTo(21f * s, 20f * s)
                moveTo(16.5f * s, 3.5f * s)
                lineTo(20.5f * s, 7.5f * s)
                lineTo(7f * s, 21f * s)
                lineTo(3f * s, 22f * s)
                lineTo(4f * s, 18f * s)
                close()
            }
        drawPath(path, tint, style = strokeStyle(s))
    }
}

/** 설정 — 톱니. 원 하나와 짧은 살 여덟 개로 그린다(SVG 톱니 경로는 이 크기에서 뭉갠다). */
@Composable
fun SettingsIcon(
    modifier: Modifier = Modifier,
    size: Dp = DEFAULT_SIZE,
    tint: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / GRID
        val cx = 12f * s
        val cy = 12f * s
        drawCircle(tint, radius = 4.2f * s, center = Offset(cx, cy), style = strokeStyle(s))
        repeat(8) { i ->
            val a = Math.toRadians(i * 45.0)
            val dx = kotlin.math.cos(a).toFloat()
            val dy = kotlin.math.sin(a).toFloat()
            drawLine(
                color = tint,
                start = Offset(cx + dx * 6.6f * s, cy + dy * 6.6f * s),
                end = Offset(cx + dx * 9.4f * s, cy + dy * 9.4f * s),
                strokeWidth = STROKE * s,
                cap = StrokeCap.Round,
            )
        }
    }
}
