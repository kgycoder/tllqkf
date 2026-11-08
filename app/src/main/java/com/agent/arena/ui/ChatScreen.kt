package com.agent.arena.ui

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.arena.AgentAccessibilityService
import com.agent.arena.AgentApp
import com.agent.arena.AgentViewModel
import com.agent.arena.ChatMessage
import com.agent.arena.Role
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

// ------------------------------------------------------------------ 인사말

private object Greetings {
    private val general = listOf(
        "무엇을 도와드릴까요?",
        "오늘은 어떤 걸 해드릴까요?",
        "필요한 게 있으면 말씀해 주세요",
        "폰으로 할 일이 있나요?",
        "다음엔 뭘 해볼까요?",
        "말만 하세요, 대신 해드릴게요",
        "어떤 작업부터 시작할까요?",
        "손이 필요하면 불러 주세요",
    )
    private var last = ""

    fun pick(): String {
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val timed = when (h) {
            in 5..10 -> listOf("좋은 아침이에요", "아침이네요, 오늘은 뭘 해볼까요?")
            in 11..16 -> listOf("좋은 오후예요", "오후에도 힘내요, 뭘 도와드릴까요?")
            in 17..21 -> listOf("좋은 저녁이에요", "오늘 하루는 어땠나요?")
            else -> listOf("아직 깨어 계시네요", "늦은 시간이네요, 무엇을 도와드릴까요?")
        }
        val pool = general + timed
        var s = pool.random()
        var guard = 0
        while (s == last && guard++ < 8) s = pool.random()
        last = s
        return s
    }
}

// ------------------------------------------------------------------ 공통 modifier

@Composable
private fun Modifier.pressable(onClick: () -> Unit, enabled: Boolean = true, pressedScale: Float = 0.92f): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = 0.55f, stiffness = 520f),
        label = "press",
    )
    return this
        .graphicsLayer { scaleX = s; scaleY = s }
        .clickable(interactionSource = src, indication = null, enabled = enabled, onClick = onClick)
}

// ------------------------------------------------------------------ 화면

@Composable
fun ChatScreen(vm: AgentViewModel, onOpenAccessibility: () -> Unit) {
    val ctl = AgentApp.instance.controller
    val p = Palette(isSystemInDarkTheme())
    val listState = rememberLazyListState()
    val a11y by AgentAccessibilityService.connected.collectAsState()
    val seen = remember { HashSet<Long>() }
    val empty = vm.messages.isEmpty()
    val greeting = remember(vm.epoch) { Greetings.pick() }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val follow = remember { mutableStateOf(true) }

    // 새 메시지가 추가되면 부드럽게 아래로
    LaunchedEffect(vm.messages.size) {
        if (vm.messages.isNotEmpty()) listState.animateScrollToItem(vm.messages.lastIndex)
    }

    // 답변이 자라는 동안 바닥을 부드럽게 따라간다 (사용자가 위로 스크롤하면 멈춤, 바닥에 닿으면 재개)
    LaunchedEffect(vm.running) {
        var idleFrames = 0
        while (vm.running || idleFrames < 220) {
            androidx.compose.runtime.withFrameNanos { }
            if (!vm.running) idleFrames++
            if (listState.isScrollInProgress) { follow.value = false; continue }
            if (!listState.canScrollForward) follow.value = true
            if (!follow.value) continue
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull() ?: continue
            if (lastVisible.index == info.totalItemsCount - 1) {
                val end = info.viewportEndOffset - info.afterContentPadding
                val dist = (lastVisible.offset + lastVisible.size - end).toFloat()
                if (dist > 0.5f) listState.dispatchRawDelta(min(dist, dist * 0.28f + 1.2f))
            } else {
                listState.dispatchRawDelta(500f)
            }
        }
    }

    Column(
        Modifier.fillMaxSize().background(p.bg)
            .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime)),
    ) {
        // 인사말/대화 영역: 입력창 "위"의 남은 공간이므로 인사말이 입력창/키보드에 가려지지 않는다.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (!empty) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = topInset + 64.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    items(vm.messages, key = { it.id }) { MessageItem(it, p, seen) }
                }
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(24.dp)
                        .background(Brush.verticalGradient(listOf(p.bg.copy(alpha = 0f), p.bg))),
                )
            }

            AnimatedVisibility(
                visible = empty,
                modifier = Modifier.fillMaxSize().padding(top = topInset + 56.dp),
                enter = EnterTransition.None,
                exit = fadeOut(tween(260)) + slideOutVertically(tween(320, easing = EaseOutSoft)) { -it / 10 },
            ) {
                val vis by transition.animateFloat(transitionSpec = { tween(320, easing = EaseOutSoft) }, label = "greetExit") { s ->
                    if (s == EnterExitState.Visible) 1f else 0f
                }
                GreetingContent(
                    text = greeting, p = p, a11yOn = a11y,
                    onOpenAccessibility = onOpenAccessibility,
                    modifier = Modifier.fillMaxSize().blurFade({ vis }, 14.dp),
                )
            }

            TopBar(
                p = p,
                onSettings = onOpenAccessibility,
                onTitle = { ctl.toggleWeb() },
                onNew = { vm.newChat() },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        InputBar(
            p = p, running = vm.running, a11yOn = a11y,
            onSettings = onOpenAccessibility,
            onSend = { vm.send(it) }, onStop = { vm.stop() },
        )
    }
}

// ------------------------------------------------------------------ 인사말 UI

@Composable
private fun GreetingContent(text: String, p: Palette, a11yOn: Boolean, onOpenAccessibility: () -> Unit, modifier: Modifier) {
    val prog = remember(text) { Animatable(0f) }
    LaunchedEffect(text) {
        delay(120)
        prog.animateTo(1f, tween(1300, easing = LinearEasing))
    }
    val v = prog.value
    val n = max(1, text.length)
    // 글자마다 시차를 두고 번지듯 나타난다
    val annotated = buildAnnotatedString {
        text.forEachIndexed { i, ch ->
            val start = (i.toFloat() / n) * 0.55f
            val a = ((v - start) / 0.45f).coerceIn(0f, 1f)
            withStyle(SpanStyle(color = p.text.copy(alpha = a))) { append(ch) }
        }
    }
    Column(modifier.padding(horizontal = 32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            Modifier.blurFade({ (prog.value * 1.8f).coerceAtMost(1f) }, 16.dp, 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            SparkIcon(p.accent, Modifier.size(30.dp), rotation = (1f - v) * -90f)
            Spacer(Modifier.width(12.dp))
            BasicText(
                annotated,
                modifier = Modifier.weight(1f, fill = false),
                style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 29.sp, lineHeight = 38.sp),
            )
        }
        if (!a11yOn) {
            Spacer(Modifier.height(30.dp))
            Reveal(delayMs = 700, durationMs = 600, maxBlur = 8.dp, rise = 10.dp) {
                Box(
                    Modifier.clip(CircleShape).border(1.dp, p.accent.copy(alpha = 0.6f), CircleShape)
                        .pressable(onOpenAccessibility, pressedScale = 0.95f)
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    BasicText("접근성 서비스 켜기", style = TextStyle(color = p.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium))
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 상단바

@Composable
private fun TopBar(p: Palette, onSettings: () -> Unit, onTitle: () -> Unit, onNew: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().background(p.bg).statusBarsPadding().height(56.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(44.dp).pressable(onSettings), contentAlignment = Alignment.Center) {
                SlidersIcon(p.text, Modifier.size(21.dp))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                BasicText(
                    "Arena Agent",
                    modifier = Modifier.pressable(onTitle, pressedScale = 0.96f).padding(horizontal = 12.dp, vertical = 8.dp),
                    style = TextStyle(color = p.text, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                )
            }
            Box(Modifier.size(44.dp).pressable(onNew), contentAlignment = Alignment.Center) {
                NewChatIcon(p.text, Modifier.size(22.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(18.dp).background(Brush.verticalGradient(listOf(p.bg, p.bg.copy(alpha = 0f)))))
    }
}

// ------------------------------------------------------------------ 입력창

@Composable
private fun InputBar(
    p: Palette, running: Boolean, a11yOn: Boolean,
    onSettings: () -> Unit, onSend: (String) -> Unit, onStop: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    val canSend = text.isNotBlank()
    val shape = RoundedCornerShape(26.dp)
    val border by animateColorAsState(if (focused) p.accent.copy(alpha = 0.55f) else p.line, tween(240), label = "border")

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
            .background(p.surface, shape)
            .border(1.dp, border, shape)
            // 줄 수가 늘어나면 컨테이너가 스프링으로 부드럽게 팽창/수축
            .animateContentSize(spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)),
    ) {
        Box(Modifier.fillMaxWidth().padding(start = 18.dp, end = 16.dp, top = 14.dp, bottom = 2.dp)) {
            val phAlpha by animateFloatAsState(if (text.isEmpty()) 1f else 0f, tween(160), label = "ph")
            BasicText(
                "무엇이든 부탁하세요",
                modifier = Modifier.graphicsLayer { alpha = phAlpha },
                style = TextStyle(color = p.sub, fontSize = 16.sp, lineHeight = 22.sp),
            )
            BasicTextField(
                value = text, onValueChange = { text = it },
                textStyle = TextStyle(color = p.text, fontSize = 16.sp, lineHeight = 22.sp),
                cursorBrush = SolidColor(p.accent), maxLines = 6,
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 8.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusChip(p, a11yOn, onSettings)
            Spacer(Modifier.weight(1f))
            SendButton(p, running, canSend) { if (running) onStop() else { onSend(text); text = "" } }
        }
    }
}

@Composable
private fun StatusChip(p: Palette, on: Boolean, onClick: () -> Unit) {
    val dot by animateColorAsState(if (on) p.accent else p.error, tween(300), label = "dot")
    Row(
        Modifier.clip(CircleShape).background(p.surface2).pressable(onClick, pressedScale = 0.95f)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(7.dp))
        BasicText(if (on) "접근성 켜짐" else "접근성 꺼짐", style = TextStyle(color = p.sub, fontSize = 12.5.sp))
    }
}

@Composable
private fun SendButton(p: Palette, running: Boolean, canSend: Boolean, onClick: () -> Unit) {
    val bg by animateColorAsState(
        when { running -> p.text; canSend -> p.accent; else -> p.surface2 }, tween(260), label = "sendBg",
    )
    val fg by animateColorAsState(
        when { running -> p.bg; canSend -> Color.White; else -> p.sub }, tween(260), label = "sendFg",
    )
    // 생성 중에는 버튼 둘레에 부드러운 회전 링 (보이지 않을 땐 컴포지션에서 제거해 프레임을 쓰지 않는다)
    val ringAlpha by animateFloatAsState(if (running) 1f else 0f, tween(320), label = "ring")
    val ringColor = p.accent

    Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
        if (ringAlpha > 0.01f) {
            val t = rememberInfiniteTransition(label = "ringSpin")
            val angle by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "ringAngle")
            Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = ringAlpha }) {
                val sw = 2.2.dp.toPx()
                drawArc(
                    ringColor, angle, 110f, false, Offset(sw / 2, sw / 2),
                    Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = androidx.compose.ui.graphics.StrokeCap.Round),
                )
            }
        }
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(bg)
                .pressable(onClick, enabled = running || canSend, pressedScale = 0.86f),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = running,
                transitionSpec = {
                    (fadeIn(tween(200, delayMillis = 60)) + scaleIn(tween(260, delayMillis = 40, easing = EaseOutSoft), initialScale = 0.4f)) togetherWith
                        (fadeOut(tween(110)) + scaleOut(tween(140), targetScale = 0.4f))
                },
                label = "sendStop",
            ) { r ->
                if (r) StopIcon(fg, Modifier.size(15.dp)) else ArrowUpIcon(fg, Modifier.size(18.dp))
            }
        }
    }
}

// ------------------------------------------------------------------ 메시지

@Composable
private fun MessageItem(m: ChatMessage, p: Palette, seen: MutableSet<Long>) {
    // 처음 보이는 메시지만 등장 애니메이션 (스크롤로 다시 보일 때는 재생하지 않음)
    val animateIn = remember(m.id) { m.id !in seen }
    LaunchedEffect(m.id) { seen.add(m.id) }
    if (m.role == Role.USER) {
        Reveal(Modifier.fillMaxWidth(), enabled = animateIn, durationMs = 460, maxBlur = 8.dp, rise = 20.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Box(Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(20.dp)).background(p.userBubble).padding(horizontal = 16.dp, vertical = 11.dp)) {
                    BasicText(m.text, style = TextStyle(color = p.text, fontSize = 16.sp, lineHeight = 23.sp))
                }
            }
        }
    } else {
        AssistantBlock(m, p, animateIn)
    }
}

@Composable
private fun AssistantBlock(m: ChatMessage, p: Palette, animateIn: Boolean) {
    val initialSteps = remember(m.id) { if (animateIn) 0 else m.steps.size }
    val waiting = m.streaming && m.text.isBlank() && m.steps.isEmpty() && !m.planning
    Column(
        Modifier.fillMaxWidth().animateContentSize(spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)),
    ) {
        AnimatedVisibility(waiting, enter = fadeIn(tween(300)), exit = fadeOut(tween(160))) {
            Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                ThinkingSpark(p.accent, Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                ShimmerText("생각하는 중", p.sub, p.text, 14.sp)
            }
        }
        m.steps.forEachIndexed { i, s ->
            val running = m.streaming && !m.planning && i == m.steps.lastIndex
            Reveal(enabled = i >= initialSteps, durationMs = 380, maxBlur = 6.dp, rise = 8.dp) {
                StepRow(s, running, p)
            }
        }
        AnimatedVisibility(m.planning, enter = fadeIn(tween(260)), exit = fadeOut(tween(140))) {
            Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                SpinnerIcon(p.accent, Modifier.size(14.dp))
                Spacer(Modifier.width(10.dp))
                ShimmerText("작업 계획 중…", p.sub, p.text, 14.sp)
            }
        }
        if (m.text.isNotBlank()) {
            StreamingText(
                text = m.text, streaming = m.streaming,
                color = if (m.isError) p.error else p.text,
                startFull = !animateIn && !m.streaming,
            )
        }
    }
}

@Composable
private fun StepRow(label: String, running: Boolean, p: Palette) {
    Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(
                targetState = running,
                transitionSpec = { (fadeIn(tween(220)) + scaleIn(tween(240, easing = EaseOutSoft), initialScale = 0.5f)) togetherWith fadeOut(tween(120)) },
                label = "stepIcon",
            ) { r ->
                if (r) SpinnerIcon(p.accent, Modifier.size(14.dp)) else CheckIcon(p.sub, Modifier.size(15.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        BasicText(label, style = TextStyle(color = p.sub, fontSize = 14.sp, lineHeight = 20.sp))
    }
}

@Composable
private fun ShimmerText(text: String, base: Color, hi: Color, size: TextUnit) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-260f, 520f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "x")
    val brush = Brush.linearGradient(listOf(base, hi, base), start = Offset(x, 0f), end = Offset(x + 260f, 0f))
    BasicText(text, style = TextStyle(brush = brush, fontSize = size))
}

/**
 * 스트리밍 답변: 글자가 도착하는 속도와 상관없이 일정한 속도로 "번지듯" 나타난다.
 * 끝부분 몇 글자는 흐림(블러) → 선명 + 투명 → 불투명으로 전환된다. 흐림은 Android 12+ 에서 적용.
 */
@Composable
private fun StreamingText(text: String, streaming: Boolean, color: Color, startFull: Boolean) {
    val fadeLen = 16f
    var revealed by remember { mutableFloatStateOf(if (startFull) text.length + fadeLen else 0f) }
    val live by rememberUpdatedState(streaming)

    LaunchedEffect(text, streaming) {
        var last = androidx.compose.runtime.withFrameNanos { it }
        while (true) {
            // 스트리밍 중에는 마지막 글자들이 살짝 흐린 채로 대기, 끝나면 끝까지 선명하게 마무리
            val goal = if (text.isEmpty()) 0f else if (live) text.length.toFloat() else text.length + fadeLen
            if (revealed > goal) revealed = goal
            if (revealed >= goal) break
            val now = androidx.compose.runtime.withFrameNanos { it }
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
            last = now
            val speed = (if (live) 70f else 150f) + (goal - revealed) * 3.5f
            revealed = min(goal, revealed + speed * dt)
        }
    }

    val n = text.length
    if (n == 0) return
    val rev = revealed
    val style = TextStyle(color = color, fontSize = 16.sp, lineHeight = 25.sp)
    if (rev >= n + fadeLen - 0.01f) {
        BasicText(text, style = style)
        return
    }
    val shown = min(n, rev.toInt())
    val solidEnd = max(0, min(shown, (rev - fadeLen).toInt()))
    val crisp = buildAnnotatedString {
        append(text.substring(0, solidEnd))
        for (i in solidEnd until shown) {
            val a = ((rev - i) / fadeLen).coerceIn(0f, 1f)
            withStyle(SpanStyle(color = color.copy(alpha = a * a))) { append(text[i]) }
        }
    }
    Box {
        BasicText(crisp, style = style)
        if (Build.VERSION.SDK_INT >= 31) {
            val blurred = buildAnnotatedString {
                append(text.substring(0, solidEnd))
                for (i in solidEnd until shown) {
                    val a = ((rev - i) / fadeLen).coerceIn(0f, 1f)
                    withStyle(SpanStyle(color = color.copy(alpha = 4f * a * (1f - a)))) { append(text[i]) }
                }
            }
            BasicText(
                blurred,
                style = style.copy(color = Color.Transparent),
                modifier = Modifier
                    .clearAndSetSemantics { }
                    .graphicsLayer {
                        val r = 3.dp.toPx()
                        renderEffect = BlurEffect(r, r, TileMode.Decal)
                        clip = false
                    },
            )
        }
    }
}
