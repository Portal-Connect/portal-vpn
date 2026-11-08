package com.multicore.vpn.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun SpeedChart(
    speedHistory: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = MaterialTheme.colorScheme.primary
) {
    if (speedHistory.size < 2) return

    val strokeWidth = 3.dp

    Canvas(modifier = modifier.fillMaxWidth().height(48.dp)) {
        val width = size.width
        val height = size.height
        val maxVal = (speedHistory.maxOrNull() ?: 100f).coerceAtLeast(10f)

        val points = speedHistory.mapIndexed { index, value ->
            val x = (index.toFloat() / (speedHistory.size - 1)) * width
            val y = height - ((value / maxVal) * (0.8f * height))
            Pair(x, y)
        }

        val strokePath = Path().apply {
            moveTo(points.first().first, points.first().second)
            for (i in 1 until points.size) {
                val p0 = points[i - 1]
                val p1 = points[i]
                val cx = (p0.first + p1.first) / 2f
                cubicTo(cx, p0.second, cx, p1.second, p1.first, p1.second)
            }
        }

        val fillPath = Path().apply {
            addPath(strokePath)
            lineTo(width, height)
            lineTo(0f, height)
            close()
        }

        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.3f), Color.Transparent)
            )
        )

        drawPath(
            path = strokePath,
            color = lineColor,
            style = Stroke(width = strokeWidth.toPx())
        )
    }
}
