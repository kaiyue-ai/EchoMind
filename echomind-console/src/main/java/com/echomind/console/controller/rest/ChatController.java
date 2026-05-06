package com.echomind.console.controller.rest;

import com.echomind.agent.orchestration.AgentOrchestrator;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.common.model.AgentMessage;
import com.echomind.memory.MemoryManager;
import com.echomind.memory.session.SessionManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 对话控制器 —— 处理用户与 Agent 之间的聊天交互。
 *
 * <p>提供两大核心能力：
 * <ol>
 *   <li><b>发送消息</b>：接收用户消息，委托 {@link AgentOrchestrator}
 *       执行完整的执行管线，返回包含 sessionId、agentId、modelId、
 *       最终回复以及各技能调用结果的统一响应。</li>
 *   <li><b>历史查询</b>：根据 sessionId 查询当前会话的完整对话上下文，
 *       由 {@link MemoryManager} 从短期窗口与长期存储中组装返回。</li>
 * </ol>
 *
 * <p>设计要点：
 * <ul>
 *   <li>若请求未提供 agentId，默认使用 "default" Agent。</li>
 *   <li>若请求未提供 sessionId，自动生成一个 UUID 作为新会话标识。</li>
 *   <li>消息体为空时返回 400 Bad Request，避免无效调用穿透到执行管线。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final AgentOrchestrator orchestrator;
    private final MemoryManager memoryManager;
    private final SessionManager sessionManager;

    public ChatController(AgentOrchestrator orchestrator, MemoryManager memoryManager,
                         SessionManager sessionManager) {
        this.orchestrator = orchestrator;
        this.memoryManager = memoryManager;
        this.sessionManager = sessionManager;
    }

    /**
     * 发送聊天消息。
     *
     * <p>接收包含 agentId（可选）、sessionId（可选）和 message（必填）的 JSON 体，
     * 经过执行管线处理后，返回结构化的响应结果。
     *
     * @param body 请求体，包含以下字段：
     *             <ul>
     *               <li>{@code agentId} —— （可选）目标 Agent 标识，默认 "default"</li>
     *               <li>{@code sessionId} —— （可选）会话标识，默认自动生成 UUID</li>
     *               <li>{@code message} —— （必填）用户消息文本</li>
     *             </ul>
     * @return 包含 sessionId、agentId、modelId、response 和 skillResults 的响应 Map；
     *         若 message 缺失或为空则返回 400
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, String> body) {
        String agentId = body.getOrDefault("agentId", "default");
        String sessionId = body.get("sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = sessionManager.createSession();
        }
        String message = body.get("message");

        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }

        PipelineContext result = orchestrator.execute(agentId, sessionId, message);
        return ResponseEntity.ok(Map.of(
            "sessionId", result.getSessionId(),
            "agentId", result.getAgentId(),
            "modelId", result.getModelId(),
            "response", result.getFinalResponse(),
            "skillResults", result.getSkillResults()
        ));
    }

    /**
     * 获取指定会话的对话历史。
     *
     * <p>从记忆管理器中加载该会话的完整消息记录（短期窗口 + 长期持久化）。
     *
     * @param sessionId 会话唯一标识
     * @return 该会话的消息列表，按时间顺序排列
     */
    @GetMapping("/{sessionId}/history")
    public ResponseEntity<List<AgentMessage>> getHistory(@PathVariable String sessionId) {
        return ResponseEntity.ok(memoryManager.getFullContext(sessionId));
    }
}
