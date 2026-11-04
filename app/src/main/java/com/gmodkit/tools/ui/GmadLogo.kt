package com.gmodkit.tools.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun GmadLogo(modifier: Modifier = Modifier, size: Dp = 48.dp) {
    Canvas(modifier = modifier.size(size)) {
        val s = this.size.minDimension
        val inset = s * 0.055f
        val panelSize = s - inset * 2f

        drawRoundRect(
            color = Color(0xFFFFAF1F),
            cornerRadius = CornerRadius(s * 0.235f)
        )

        drawRoundRect(
            color = Color(0xFF121110),
            topLeft = Offset(inset, inset),
            size = Size(panelSize, panelSize),
            cornerRadius = CornerRadius(s * 0.19f)
        )

        val stroke = s * 0.09f
        val letters = Color(0xFFFFF9EE)
        val pen = Stroke(width = stroke, cap = StrokeCap.Round)

        val gCenter = Offset(s * 0.335f, s * 0.5f)
        val gRadius = s * 0.172f
        drawArc(
            color = letters,
            startAngle = 0f,
            sweepAngle = 310f,
            useCenter = false,
            topLeft = Offset(gCenter.x - gRadius, gCenter.y - gRadius),
            size = Size(gRadius * 2f, gRadius * 2f),
            style = pen
        )
        drawLine(
            color = letters,
            start = Offset(gCenter.x + gRadius * 0.08f, gCenter.y),
            end = Offset(gCenter.x + gRadius, gCenter.y),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )

        val eLeft = s * 0.625f
        val eRight = s * 0.862f
        val eTop = s * 0.30f
        val eBottom = s * 0.70f
        val eMid = s * 0.5f
        drawLine(letters, Offset(eLeft, eTop), Offset(eLeft, eBottom), stroke, StrokeCap.Round)
        drawLine(letters, Offset(eLeft, eTop), Offset(eRight, eTop), stroke, StrokeCap.Round)
        drawLine(
            letters,
            Offset(eLeft, eMid),
            Offset(eLeft + (eRight - eLeft) * 0.76f, eMid),
            stroke,
            StrokeCap.Round
        )
        drawLine(letters, Offset(eLeft, eBottom), Offset(eRight, eBottom), stroke, StrokeCap.Round)
    }
}
