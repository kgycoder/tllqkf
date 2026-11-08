package com.agent.arena

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

/** 화면 읽기(UI 트리 덤프) + 탭/스와이프/입력/전역 키/앱 실행 등 기기 조작 실행기. */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private val _connected = MutableStateFlow(false)
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        @Volatile var instance: AgentAccessibilityService? = null
            private set

        private const val MAX_NODES = 160
    }

    private val nodes = HashMap<Int, AccessibilityNodeInfo>()

    // Agent Action Cursor (접근성 오버레이)
    private var cursorInst: AgentCursorOverlay? = null
    private val cursor: AgentCursorOverlay
        get() = cursorInst ?: AgentCursorOverlay(this).also { cursorInst = it }

    fun cursorShow(label: String) { cursor.show(); cursor.setLabel(label, CursorState.WORKING) }
    fun cursorLabel(label: String, state: CursorState = CursorState.WORKING) { cursorInst?.setLabel(label, state) }
    fun cursorHide(delayMs: Long = 0) { cursorInst?.hide(delayMs) }

    private suspend fun cursorMoveTo(x: Float, y: Float) {
        cursor.show()
        cursor.moveTo(x, y)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _connected.value = true
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cursorInst?.destroy()
        cursorInst = null
        instance = null
        _connected.value = false
        return super.onUnbind(intent)
    }

    @Volatile private var lastEventAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> lastEventAt = SystemClock.uptimeMillis()
        }
    }

    /**
     * 고정 delay 대신 "화면이 조용해질 때까지" 적응형으로 기다린다.
     * minMs 동안은 무조건 대기(전환 시작 대기), 이후 quietMs 동안 접근성 이벤트가 없으면 종료, maxMs 에서 강제 종료.
     */
    private suspend fun settle(minMs: Long = 90, quietMs: Long = 140, maxMs: Long = 1000) {
        val start = SystemClock.uptimeMillis()
        delay(minMs)
        while (true) {
            val now = SystemClock.uptimeMillis()
            if (now - start >= maxMs) return
            if (now - lastEventAt >= quietMs) return
            delay(25)
        }
    }

    private suspend fun waitForeground(pkg: String, timeoutMs: Long): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (rootInActiveWindow?.packageName?.toString() == pkg) return true
            delay(60)
        }
        return false
    }
    override fun onInterrupt() {}

    // ------------------------------------------------------------ screen dump

    fun dumpScreen(): String {
        val root = rootInActiveWindow
            ?: return "(화면 정보를 읽을 수 없음: 잠금/보안 화면이거나 전환 중일 수 있음. wait 후 다시 확인)"
        val pkg = root.packageName?.toString().orEmpty()
        nodes.clear()
        if (pkg == packageName) {
            return "foreground_package: $pkg\n(현재 앞에 있는 화면은 에이전트 앱 자신입니다. 기기 조작이 필요하면 launch_app 또는 key home 으로 이동하세요.)"
        }
        val sb = StringBuilder()
        sb.appendLine("foreground_package: $pkg")
        var id = 0

        fun walk(n: AccessibilityNodeInfo) {
            if (!n.isVisibleToUser) return
            val text = n.text?.toString()?.replace("\n", " ")?.take(100)
            val desc = n.contentDescription?.toString()?.replace("\n", " ")?.take(100)
            val interesting = !text.isNullOrBlank() || !desc.isNullOrBlank() ||
                n.isClickable || n.isEditable || n.isScrollable || n.isCheckable
            if (interesting && id < MAX_NODES) {
                id++
                nodes[id] = n
                val r = Rect()
                n.getBoundsInScreen(r)
                val flags = ArrayList<String>()
                if (n.isClickable) flags.add("click")
                if (n.isLongClickable) flags.add("long")
                if (n.isEditable) flags.add("edit")
                if (n.isScrollable) flags.add("scroll")
                if (n.isCheckable) flags.add(if (n.isChecked) "checked" else "unchecked")
                if (n.isFocused) flags.add("focused")
                if (!n.isEnabled) flags.add("disabled")
                sb.append("[$id] ${n.className?.toString()?.substringAfterLast('.')}")
                if (!text.isNullOrBlank()) sb.append(" text=\"$text\"")
                if (!desc.isNullOrBlank()) sb.append(" desc=\"$desc\"")
                sb.append(" center=(${r.centerX()},${r.centerY()})")
                if (flags.isNotEmpty()) sb.append(" ${flags.joinToString(",")}")
                sb.appendLine()
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        if (id == 0) sb.appendLine("(상호작용 가능한 요소가 보이지 않음)")
        if (id >= MAX_NODES) sb.appendLine("(요소가 많아 일부만 표시됨. scroll 로 더 확인)")
        return sb.toString().trimEnd()
    }

    // ------------------------------------------------------------ command execution

    /** 명령 1개를 실행하고 사람이 읽을 수 있는 결과 문자열("ok: ..." / "error: ...")을 반환. */
    suspend fun execute(c: JSONObject): String {
        val a = c.optString("action").lowercase().trim()
        return try {
            when (a) {
                "tap", "click" -> tap(c, longPress = false)
                "tap_text", "click_text" -> tapText(c, longPress = false)
                "long_press", "longpress" -> tap(c, longPress = true)
                "swipe" -> swipe(c)
                "scroll" -> scroll(c.optString("direction", "down"))
                "type", "input", "set_text" -> typeText(c)
                "key" -> key(c.optString("key"))
                "back", "home", "recents", "notifications", "quick_settings", "lock_screen" -> key(a)
                "launch_app", "open_app" -> launchApp(c)
                "open_url" -> openUrl(c)
                "read_screen", "screen" -> dumpScreen()
                "wait" -> {
                    val ms = c.optLong("ms", 1000).coerceIn(100, 10_000)
                    delay(ms); "ok: waited ${ms}ms"
                }
                // 시스템 API / 앱 인텐트 빠른 경로 (UI 조작 없음)
                else -> SystemApi.handle(this, c) ?: "error: unknown action '$a'"
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            "error: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private suspend fun tap(c: JSONObject, longPress: Boolean): String {
        val id = c.optInt("id", -1)
        if (id > 0) {
            val nd = nodes[id] ?: return "error: id $id 는 최신 DEVICE_STATE 에 없음(만료)"
            val r = Rect(); nd.getBoundsInScreen(r)
            return clickNode(nd, r.centerX().toFloat(), r.centerY().toFloat(), longPress, "id $id")
        }
        if (!c.has("x") || !c.has("y")) return "error: tap requires id or x,y"
        return clickNode(null, c.getDouble("x").toFloat(), c.getDouble("y").toFloat(), longPress, "point")
    }

    /** 화면에 보이는 텍스트/설명으로 바로 탭: 최신 id 목록 없이도 동작해 왕복 한 번을 줄인다. */
    private suspend fun tapText(c: JSONObject, longPress: Boolean): String {
        val q = c.optString("text").trim()
        if (q.isEmpty()) return "error: tap_text requires text"
        val root = rootInActiveWindow ?: return "error: 활성 창을 읽을 수 없음"
        val hits = root.findAccessibilityNodeInfosByText(q).filter { it.isVisibleToUser }
        if (hits.isEmpty()) return "error: '$q' 텍스트가 화면에 없음 (read_screen 으로 확인)"
        val exact = hits.firstOrNull {
            it.text?.toString()?.trim().equals(q, true) || it.contentDescription?.toString()?.trim().equals(q, true)
        }
        val pick = exact ?: hits[c.optInt("index", 0).coerceIn(0, hits.size - 1)]
        val r = Rect(); pick.getBoundsInScreen(r)
        return clickNode(pick, r.centerX().toFloat(), r.centerY().toFloat(), longPress, "'$q'")
    }

    private suspend fun clickNode(node: AccessibilityNodeInfo?, x: Float, y: Float, longPress: Boolean, label: String): String {
        cursorMoveTo(x, y)
        cursor.press()
        if (node != null && !longPress) {
            var n: AccessibilityNodeInfo? = node
            while (n != null && !n.isClickable) n = n.parent
            if (n != null && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                settle(100, 140, 900); return "ok: clicked $label"
            }
        }
        val ok = gesture(point(x, y), if (longPress) 700 else 50)
        settle(100, 140, 900)
        val what = if (longPress) "long-pressed" else "tapped"
        return if (ok) "ok: $what $label (${x.toInt()},${y.toInt()})" else "error: gesture failed"
    }

    private suspend fun swipe(c: JSONObject): String {
        val x1 = c.getDouble("x1").toFloat(); val y1 = c.getDouble("y1").toFloat()
        val x2 = c.getDouble("x2").toFloat(); val y2 = c.getDouble("y2").toFloat()
        return swipeInternal(x1, y1, x2, y2, c.optLong("duration_ms", 350).coerceIn(50, 3000), "swiped")
    }

    private suspend fun swipeInternal(x1: Float, y1: Float, x2: Float, y2: Float, dur: Long, label: String): String {
        cursorMoveTo(x1, y1)
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        cursor.glideTo(x2, y2) // 제스처가 진행되는 동안 커서도 같이 이동
        val ok = gesture(p, dur)
        settle(60, 120, 700)
        return if (ok) "ok: $label" else "error: gesture failed"
    }

    /** direction = 콘텐츠를 이동하고 싶은 방향 (down: 페이지 아래쪽으로 = 손가락은 위로). */
    private suspend fun scroll(direction: String): String {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat(); val h = dm.heightPixels.toFloat()
        return when (direction.lowercase()) {
            "down" -> swipeInternal(w / 2, h * 0.72f, w / 2, h * 0.28f, 350, "scrolled down")
            "up" -> swipeInternal(w / 2, h * 0.28f, w / 2, h * 0.72f, 350, "scrolled up")
            "right" -> swipeInternal(w * 0.8f, h / 2, w * 0.2f, h / 2, 350, "scrolled right")
            "left" -> swipeInternal(w * 0.2f, h / 2, w * 0.8f, h / 2, 350, "scrolled left")
            else -> "error: direction must be up/down/left/right"
        }
    }

    private suspend fun typeText(c: JSONObject): String {
        val text = c.optString("text")
        val id = c.optInt("id", -1)
        val node: AccessibilityNodeInfo? = when {
            id > 0 -> nodes[id]
            else -> rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: nodes.values.firstOrNull { it.isEditable }
        }
        if (node == null) return "error: 입력 가능한 필드를 찾지 못함 (먼저 필드를 tap 하세요)"
        val r = Rect(); node.getBoundsInScreen(r)
        if (r.width() > 0 && r.height() > 0) {
            cursorMoveTo(r.centerX().toFloat(), r.centerY().toFloat())
            cursor.press()
        }
        if (!node.isFocused) node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val base = if (c.optBoolean("append", false) && !node.isShowingHintText) node.text?.toString().orEmpty() else ""
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, base + text)
        }
        val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        if (!ok) return "error: ACTION_SET_TEXT 실패"
        if (c.optBoolean("submit", false) && Build.VERSION.SDK_INT >= 30) {
            delay(120)
            node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
        }
        settle(50, 100, 600)
        return "ok: typed ${text.length} chars"
    }

    private suspend fun key(k: String): String {
        val action = when (k.lowercase()) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return "error: lock_screen requires Android 9+"
            else -> return "error: unknown key '$k'"
        }
        val ok = performGlobalAction(action)
        settle(160, 180, 1200)
        return if (ok) "ok: key $k" else "error: key $k failed"
    }

    private suspend fun launchApp(c: JSONObject): String {
        val pm = packageManager
        var pkg = c.optString("package")
        if (pkg.isBlank()) {
            val name = c.optString("name").trim()
            if (name.isBlank()) return "error: launch_app requires package or name"
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val list = pm.queryIntentActivities(main, 0)
            val hit = list.firstOrNull { it.loadLabel(pm).toString().equals(name, true) }
                ?: list.firstOrNull { it.loadLabel(pm).toString().contains(name, true) }
                ?: return "error: '$name' 앱을 찾지 못함"
            pkg = hit.activityInfo.packageName
        }
        val intent = pm.getLaunchIntentForPackage(pkg) ?: return "error: $pkg 실행 불가(미설치/실행 인텐트 없음)"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        startActivity(intent)
        val fg = waitForeground(pkg, 3000)
        settle(0, 200, 900)
        return if (fg) "ok: launched $pkg (in foreground)" else "ok: launched $pkg (foreground not confirmed yet)"
    }

    private suspend fun openUrl(c: JSONObject): String {
        val url = c.optString("url")
        if (url.isBlank()) return "error: url is empty"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        c.optString("package").takeIf { it.isNotBlank() }?.let { i.setPackage(it) }
        startActivity(i)
        settle(250, 220, 1200)
        return "ok: opened $url"
    }

    // ------------------------------------------------------------ gestures

    private fun point(x: Float, y: Float) = Path().apply { moveTo(x, y) }

    private suspend fun gesture(path: Path, durationMs: Long): Boolean =
        suspendCancellableCoroutine { cont ->
            val g = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(1)))
                .build()
            val dispatched = dispatchGesture(g, object : GestureResultCallback() {
                override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
            }, null)
            if (!dispatched && cont.isActive) cont.resume(false)
        }
}
