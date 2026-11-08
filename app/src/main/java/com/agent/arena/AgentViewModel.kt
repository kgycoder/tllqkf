package com.agent.arena

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class Role { USER, AGENT }

data class ChatMessage(
    val id: Long,
    val role: Role,
    val text: String = "",
    val steps: List<String> = emptyList(),
    val streaming: Boolean = false,
    val planning: Boolean = false,
    val isError: Boolean = false,
)

class AgentViewModel : ViewModel() {

    companion object { private const val MAX_STEPS = 40 }

    private val app get() = AgentApp.instance
    private val ctl get() = AgentApp.instance.controller

    val messages = mutableStateListOf<ChatMessage>()
    var running by mutableStateOf(false)
        private set

    /** 새 대화마다 증가: 인사말 재선택 등에 사용 */
    var epoch by mutableIntStateOf(0)
        private set

    private var job: Job? = null
    private var sessionStarted = false
    private var nextId = 1L

    fun send(text: String) {
        val t = text.trim()
        if (t.isEmpty() || running) return
        messages.add(ChatMessage(nextId++, Role.USER, t))
        val idx = messages.size
        messages.add(ChatMessage(nextId++, Role.AGENT, streaming = true))
        running = true
        job = viewModelScope.launch {
            try {
                runAgent(t, idx)
            } finally {
                running = false
                AgentAccessibilityService.instance?.cursorHide(1300)
            }
        }
    }

    fun stop() {
        if (!running) return
        job?.cancel()
        viewModelScope.launch { ctl.stopGeneration() }
    }

    /** 새 채팅 = LMArena URL 을 다시 열어 새 세션 시작. */
    fun newChat() {
        job?.cancel()
        running = false
        messages.clear()
        sessionStarted = false
        epoch++
        viewModelScope.launch {
            ctl.stopGeneration()
            ctl.newChat()
        }
    }

    // ---------------------------------------------------------------- agent loop

    private fun upd(i: Int, f: (ChatMessage) -> ChatMessage) {
        if (i in 0 until messages.size) messages[i] = f(messages[i])
    }

    private fun finish(i: Int, text: String, error: Boolean) {
        upd(i) { it.copy(text = text, streaming = false, planning = false, isError = error) }
    }

    private suspend fun runAgent(userText: String, idx: Int) {
        var outgoing = if (!sessionStarted) AgentPrompt.first(userText, app) else AgentPrompt.followUp(userText, app)
        var step = 0
        var formatRetries = 0
        try {
            while (true) {
                step++
                if (step > MAX_STEPS) {
                    finish(idx, "최대 단계($MAX_STEPS)에 도달해 작업을 중단했습니다.", true); return
                }
                val ticket = ctl.send(outgoing)
                if (ticket == null) {
                    finish(idx, "LMArena로 메시지를 보내지 못했습니다. 상단 'Agent' 버튼으로 LMArena 페이지 상태(로그인/캡차)를 확인하세요.", true)
                    return
                }
                sessionStarted = true

                val raw = ctl.awaitResponse(ticket, isComplete = { WorkParser.parse(it).block != null }) { partial ->
                    upd(idx) {
                        it.copy(
                            text = WorkParser.visibleStreaming(partial),
                            planning = WorkParser.isPlanning(partial),
                        )
                    }
                }
                if (raw.isBlank()) {
                    finish(idx, "LMArena 응답을 받지 못했습니다. (시간 초과 또는 사이트 오류)\n진단: ${ctl.debug(ticket)}", true); return
                }

                val parsed = WorkParser.parse(raw)
                val block = parsed.block
                if (block == null) {
                    if (parsed.looksBroken && formatRetries < 2) {
                        formatRetries++
                        outgoing = AgentPrompt.formatError()
                        continue
                    }
                    // work 블록이 없으면 일반 대화 답변으로 간주
                    finish(idx, parsed.visible.ifBlank { raw }, false)
                    return
                }
                formatRetries = 0

                val terminal = block.status == "done" || block.status == "failed" || block.status == "need_user"
                val hasCmds = block.commands.isNotEmpty()
                // 종료 상태의 답변 텍스트는 지우지 않고 유지 → 스트리밍 애니메이션이 끊기지 않는다
                upd(idx) {
                    it.copy(
                        steps = if (hasCmds || !terminal) it.steps + block.summary.ifBlank { block.status } else it.steps,
                        text = if (terminal) parsed.visible else "",
                        planning = false,
                    )
                }

                // API 전용 턴에는 커서 오버레이/화면 덤프가 필요 없다
                val needsScreen = block.commands.any { !SystemApi.isSilent(it.optString("action")) }
                val svc = AgentAccessibilityService.instance
                if (needsScreen) svc?.cursorShow(block.summary.ifBlank { "작업 중" })
                val exec = if (hasCmds) executeAll(block.commands) else Exec("[]", false)

                when (block.status) {
                    "done" -> if (!exec.error) {
                        if (needsScreen) svc?.cursorLabel("작업 완료", CursorState.DONE)
                        finish(idx, parsed.visible.ifBlank { block.summary }, false); return
                    } else {
                        // 성공을 가정하고 done 을 냈지만 명령이 실패 → 오류를 알려 다시 시도하게 한다
                        upd(idx) { it.copy(text = "") }
                    }
                    "failed" -> { if (needsScreen) svc?.cursorLabel("작업 실패", CursorState.FAILED); finish(idx, parsed.visible.ifBlank { block.summary }, true); return }
                    "need_user" -> { if (needsScreen) svc?.cursorLabel("확인이 필요해요", CursorState.ASK); finish(idx, parsed.visible.ifBlank { block.summary }, false); return }
                }
                if (needsScreen) svc?.cursorLabel("다음 작업 생각 중…", CursorState.THINKING)
                outgoing = AgentPrompt.result(step, exec.json, includeScreen = needsScreen)
            }
        } catch (e: CancellationException) {
            AgentAccessibilityService.instance?.cursorLabel("중단됨", CursorState.STOPPED)
            upd(idx) { it.copy(streaming = false, planning = false, text = it.text.ifBlank { "(중단됨)" }) }
            throw e
        } catch (e: Exception) {
            finish(idx, "오류가 발생했습니다: ${e.javaClass.simpleName}: ${e.message}", true)
        }
    }

    private class Exec(val json: String, val error: Boolean)

    private suspend fun executeAll(cmds: List<JSONObject>): Exec {
        val svc = AgentAccessibilityService.instance
        val arr = JSONArray()
        var error = false
        for (c in cmds.take(10)) {
            val r = svc?.execute(c) ?: "error: accessibility service is not enabled"
            arr.put(JSONObject().put("action", c.optString("action")).put("result", r))
            if (r.startsWith("error")) { error = true; break }
        }
        return Exec(arr.toString(), error)
    }
}
