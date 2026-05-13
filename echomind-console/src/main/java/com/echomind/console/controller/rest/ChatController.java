package com.echomind.console.controller.rest;

import com.echomind.common.model.AgentMessage;
import com.echomind.common.model.MessageAttachment;
import com.echomind.common.model.SessionSummary;
import com.echomind.console.dto.ChatMessageRequest;
import com.echomind.console.dto.ChatSubmitResponse;
import com.echomind.console.dto.ChatSyncResponse;
import com.echomind.console.service.ChatApplicationService;
import com.echomind.console.service.SsePushService;
import com.echomind.memory.MemoryManager;
import com.echomind.skill.storage.ObjectStorageService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class ChatController {

    private final ChatApplicationService chatService;
    private final MemoryManager memoryManager;
    private final SsePushService ssePushService;
    private final ObjectStorageService storageService;

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
        return ssePushService.createEmitter(requestId);
    }

    /**
     * 直接流式聊天入口：跳过 RabbitMQ，边生成边通过 SSE 推送文本片段。
     *
     * <p>这条链路不会只等最终结果，而是直接订阅 {@code provider.stream()}，
     * 适合前端做逐字/逐片段输出。</p>
     *
     * <p>SSE 事件格式：
     * <ul>
     *   <li>{@code data: {"token":"Hello"}}：一个文本片段</li>
     *   <li>{@code data: [DONE]}：流式输出结束</li>
     *   <li>{@code data: [ERROR] message}：流式输出失败</li>
     * </ul>
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatMessageRequest request) {
        ChatApplicationService.StreamingChat stream;
        try {
            stream = chatService.prepareStream(request);
        } catch (IllegalArgumentException e) {
            SseEmitter badRequest = new SseEmitter();
            badRequest.completeWithError(e);
            return badRequest;
        }

        SseEmitter emitter = new SseEmitter(300000L);
        try {
            emitter.send(SseEmitter.event().name("meta").data(Map.of("sessionId", stream.sessionId())));
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }

        stream.tokens().subscribe(
            token -> {
                try {
                    emitter.send(SseEmitter.event().name("token").data(Map.of("token", token)));
                } catch (IOException e) {
                    emitter.completeWithError(e);
                }
            },
            error -> {
                try {
                    emitter.send(SseEmitter.event().name("error").data(Map.of("error", error.getMessage())));
                } catch (IOException ignored) {
                }
                emitter.completeWithError(error);
            },
            () -> {
                try {
                    emitter.send(SseEmitter.event().name("result").data("[DONE]"));
                } catch (IOException ignored) {
                }
                emitter.complete();
            }
        );

        return emitter;
    }

    /** 列出所有有记忆的对话摘要。 */
    @GetMapping("/sessions")
    public ResponseEntity<List<SessionSummary>> listSessions() {
        try {
            return ResponseEntity.ok(memoryManager.listSessions());
        } catch (Exception e) {
            log.warn("Failed to list chat sessions: {}", e.getMessage());
            return ResponseEntity.ok(List.of());
        }
    }

    /** 获取指定对话的历史。 */
    @GetMapping("/{sessionId}/history")
    public ResponseEntity<List<AgentMessage>> getHistory(@PathVariable String sessionId) {
        return ResponseEntity.ok(memoryManager.getFullContext(sessionId).stream()
            .map(this::refreshAttachmentUrls)
            .toList());
    }

    /** 删除指定对话的完整历史、向量缓存以及关联附件对象。 */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Map<String, String>> deleteSession(@PathVariable String sessionId) {
        return ResponseEntity.ok(chatService.deleteSession(sessionId));
    }

    private AgentMessage refreshAttachmentUrls(AgentMessage message) {
        if (message.attachments() == null || message.attachments().isEmpty()) {
            return message;
        }
        List<MessageAttachment> refreshed = message.attachments().stream()
            .map(this::refreshAttachmentUrl)
            .toList();
        return new AgentMessage(message.role(), message.content(), message.timestamp(),
            message.metadata(), refreshed);
    }

    private MessageAttachment refreshAttachmentUrl(MessageAttachment attachment) {
        if (attachment == null || attachment.uri() == null || !storageService.supports(attachment.uri())) {
            return attachment;
        }
        return attachment.withUrl(storageService.urlFor(attachment.uri(), Duration.ofDays(7)));
    }
}
