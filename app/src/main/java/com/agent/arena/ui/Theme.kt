package com.agent.arena.ui

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** 순수 검정 / 블랙그레이 / 화이트 + 포인트 블루. */
class Palette(dark: Boolean) {
    val bg = if (dark) Color(0xFF000000) else Color(0xFFFFFFFF)
    val surface = if (dark) Color(0xFF141416) else Color(0xFFF5F5F6)
    val surface2 = if (dark) Color(0xFF232326) else Color(0xFFE8E8EB)
    val line = if (dark) Color(0xFF2B2B2F) else Color(0xFFE2E2E6)
    val text = if (dark) Color(0xFFF5F5F7) else Color(0xFF0A0A0A)
    val sub = if (dark) Color(0xFF8E8E93) else Color(0xFF6B6B70)
    val accent = if (dark) Color(0xFF3B82F6) else Color(0xFF2563EB)
    val userBubble = surface2
    val error = Color(0xFFFF5A5F)
}

/** 부드럽게 감속하는 공통 이징 (Claude 앱의 느낌). */
val EaseOutSoft = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)

/**
 * progress 1 = 완전히 보임. alpha + 위로 떠오르기 + 블러를 한 번에 처리한다.
 * progress 는 람다로 받아 draw 단계에서만 읽는다(프레임마다 리컴포지션 없음). 블러는 Android 12+ 에서만 적용.
 */
fun Modifier.blurFade(progress: () -> Float, maxBlur: Dp = 12.dp, rise: Dp = 0.dp): Modifier = graphicsLayer {
    val p = progress().coerceIn(0f, 1f)
    alpha = p
    translationY = (1f - p) * rise.toPx()
    if (Build.VERSION.SDK_INT >= 31) {
        val r = (1f - p) * maxBlur.toPx()
        renderEffect = if (r > 0.5f) BlurEffect(r, r, TileMode.Decal) else null
    }
    clip = false
}

/** 처음 나타날 때 한 번만 블러 + 페이드 + 상승으로 등장. enabled=false 면 즉시 표시(스크롤로 다시 보일 때 재생 방지). */
@Composable
fun Reveal(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    delayMs: Int = 0,
    durationMs: Int = 520,
    maxBlur: Dp = 12.dp,
    rise: Dp = 12.dp,
    content: @Composable () -> Unit,
) {
    val p = remember { Animatable(if (enabled) 0f else 1f) }
    LaunchedEffect(Unit) {
        if (p.value < 1f) {
            if (delayMs > 0) delay(delayMs.toLong())
            p.animateTo(1f, tween(durationMs, easing = EaseOutSoft))
        }
    }
    Box(modifier.blurFade({ p.value }, maxBlur, rise)) { content() }
}
