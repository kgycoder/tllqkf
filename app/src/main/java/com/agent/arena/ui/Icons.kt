package com.agent.arena.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

@Composable
fun PlusIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val sw = 2.dp.toPx()
    drawLine(color, Offset(size.width * 0.15f, size.height / 2), Offset(size.width * 0.85f, size.height / 2), sw, StrokeCap.Round)
    drawLine(color, Offset(size.width / 2, size.height * 0.15f), Offset(size.width / 2, size.height * 0.85f), sw, StrokeCap.Round)
}

@Composable
fun SlidersIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val sw = 2.dp.toPx()
    val w = size.width; val h = size.height
    drawLine(color, Offset(w * 0.1f, h * 0.32f), Offset(w * 0.9f, h * 0.32f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.1f, h * 0.68f), Offset(w * 0.9f, h * 0.68f), sw, StrokeCap.Round)
    drawCircle(color, radius = 3.2.dp.toPx(), center = Offset(w * 0.35f, h * 0.32f))
    drawCircle(color, radius = 3.2.dp.toPx(), center = Offset(w * 0.68f, h * 0.68f))
}

/** 새 대화(연필 + 사각형). */
@Composable
fun NewChatIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val sw = 1.9.dp.toPx()
    val w = size.width; val h = size.height
    drawRoundRect(color, topLeft = Offset(w * 0.08f, w * 0.14f), size = Size(w * 0.66f, h * 0.76f),
        cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawLine(color, Offset(w * 0.42f, h * 0.60f), Offset(w * 0.90f, h * 0.12f), sw * 1.5f, StrokeCap.Round)
}

@Composable
fun ArrowUpIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val sw = 2.2f.dp.toPx()
    val w = size.width; val h = size.height
    drawLine(color, Offset(w / 2, h * 0.88f), Offset(w / 2, h * 0.14f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.16f, h * 0.46f), Offset(w / 2, h * 0.12f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.84f, h * 0.46f), Offset(w / 2, h * 0.12f), sw, StrokeCap.Round)
}

@Composable
fun StopIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    drawRoundRect(color, topLeft = Offset(size.width * 0.18f, size.height * 0.18f),
        size = Size(size.width * 0.64f, size.height * 0.64f), cornerRadius = CornerRadius(3.dp.toPx()))
}

@Composable
fun CheckIcon(color: Color, modifier: Modifier = Modifier) = Canvas(modifier) {
    val sw = 1.8.dp.toPx()
    val w = size.width; val h = size.height
    drawLine(color, Offset(w * 0.18f, h * 0.54f), Offset(w * 0.42f, h * 0.76f), sw, StrokeCap.Round)
    drawLine(color, Offset(w * 0.42f, h * 0.76f), Offset(w * 0.84f, h * 0.26f), sw, StrokeCap.Round)
}

/** 포인트 블루 스파크(로고 대용). rotation 은 도(degree). */
@Composable
fun SparkIcon(color: Color, modifier: Modifier = Modifier, rotation: Float = 0f) = Canvas(modifier) {
    val sw = size.minDimension * 0.11f
    val c = center
    val r = size.minDimension * 0.46f
    rotate(rotation, c) {
        for (k in 0 until 4) {
            rotate(k * 45f, c) {
                drawLine(color, Offset(c.x, c.y - r), Offset(c.x, c.y + r), sw, StrokeCap.Round)
            }
        }
    }
}

/** 생각 중 표시: 천천히 돌며 숨쉬는 스파크. */
@Composable
fun ThinkingSpark(color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "spark")
    val rot by t.animateFloat(0f, 90f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "rot")
    val s by t.animateFloat(0.82f, 1.08f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale")
    SparkIcon(color, modifier.graphicsLayer { scaleX = s; scaleY = s }, rotation = rot)
}

/** 진행 중 스피너(호). */
@Composable
fun SpinnerIcon(color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "a")
    Canvas(modifier) {
        val sw = 2.dp.toPx()
        val inset = sw / 2
        drawArc(color.copy(alpha = 0.18f), 0f, 360f, false, Offset(inset, inset), Size(size.width - sw, size.height - sw), style = Stroke(sw))
        drawArc(color, a, 100f, false, Offset(inset, inset), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
    }
}
