package com.echomind.console.controller.rest;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.SessionSummary;
import com.echomind.console.auth.AuthContext;
import com.echomind.console.dto.ChatMessageRequest;
import com.echomind.console.dto.ChatSubmitResponse;
import com.echomind.console.dto.ChatSyncResponse;
import com.echomind.console.service.ChatApplicationService;
import com.echomind.console.service.MemoryApplicationService;
import com.echomind.console.service.SsePushService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

/**
 * 聊天控制器，负责把前端/调用方的消息送进 Agent 执行链路。
 *
 * <p>当前支持两种入口：
 * <ol>
 *   <li><b>异步模式（RabbitMQ）</b>：{@code POST /api/chat} 只投递消息并立即返回 requestId，
 *       客户端再通过 {@code GET /api/chat/stream/{requestId}} 等最终结果。</li>
 *   <li><b>同步模式</b>：{@code POST /api/chat/sync} 直接执行 Agent 管线并返回完整回复，
 *       适合调试或不想接消息队列的简单集成。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatApplicationService chatService; // 聊天应用服务
    private final MemoryApplicationService memoryService; // 记忆应用服务
    private final SsePushService ssePushService; // SSE 推送服务

    /**
     * 异步聊天入口：把请求写入 RabbitMQ，然后立刻返回 requestId。
     * 客户端拿到 requestId 后，再订阅 SSE 等待最终结果。
     */
    @PostMapping
    public ResponseEntity<ChatSubmitResponse> chat(@RequestBody ChatMessageRequest request) {
        return ResponseEntity.ok(chatService.submitAsync(request));
    }

    /**
     * 同步聊天入口：直接执行 Agent 管线并返回完整结果。
     * 本地调试或 RabbitMQ 不可用时可以走这条链路。
     */
    @PostMapping("/sync")
    public ResponseEntity<ChatSyncResponse> chatSync(@RequestBody ChatMessageRequest request) {
        return ResponseEntity.ok(chatService.executeSync(request));
    }

    /**
     * 异步结果 SSE 入口：根据 requestId 推送 RabbitMQ 消费完成后的最终响应。
     */
    @GetMapping(value = "/stream/{requestId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamResult(@PathVariable String requestId) {
        return ssePushService.createEmitter(requestId, AuthContext.userId());
    }

    /** 列出所有有记忆的对话摘要。 */
    @GetMapping("/sessions")
    public ResponseEntity<List<SessionSummary>> listSessions() {
        return ResponseEntity.ok(memoryService.listSessions());
    }

    /** 获取指定对话的历史。 */
    @GetMapping("/{sessionId}/history")
    public ResponseEntity<List<AgentMessage>> getHistory(@PathVariable String sessionId) {
        return ResponseEntity.ok(memoryService.getChatHistory(sessionId));
    }

    /** 删除指定对话的完整历史、向量缓存以及关联附件对象。 */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Map<String, String>> deleteSession(@PathVariable String sessionId) {
        return ResponseEntity.ok(chatService.deleteSession(sessionId));
    }
}
