package com.echomind.console.controller.rest;

import com.echomind.common.model.AgentMessage;
import com.echomind.memory.MemoryManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 记忆管理控制器 —— 提供会话记忆的查询与清除 API。
 *
 * <p>记忆系统是 EchoMind Agent 上下文感知能力的基础，
 * 由短期窗口（Deque 实现的有界对话窗口）和长期持久化（文件/Redis）双层组成。
 *
 * <p>本控制器提供：
 * <ol>
 *   <li><b>查询记忆</b>：加载指定会话的完整对话上下文</li>
 *   <li><b>清除记忆</b>：清空指定会话的所有记忆记录</li>
 * </ol>
 *
 * <p>设计要点：
 * <ul>
 *   <li>查询操作调用 {@link MemoryManager#getFullContext}，
 *       同时从短期窗口和长期存储中加载消息。</li>
 *   <li>清除操作会同时清理短期窗口和长期持久化数据。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/memory")
public class MemoryController {

    /** 记忆管理器，统一管理短期窗口与长期持久化 */
    private final MemoryManager memoryManager;

    /**
     * 构造记忆管理控制器。
     *
     * @param memoryManager 记忆管理器
     */
    public MemoryController(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    /**
     * 查询指定会话的完整对话记忆。
     *
     * <p>返回该会话中所有已记录的消息，包含用户消息、
     * Agent 回复及技能调用结果。
     *
     * @param sessionId 会话唯一标识
     * @return 该会话的消息列表
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<List<AgentMessage>> getMemory(@PathVariable String sessionId) {
        return ResponseEntity.ok(memoryManager.getFullContext(sessionId));
    }

    /**
     * 清除指定会话的所有记忆。
     *
     * <p>此操作不可逆，将删除短期窗口和长期持久化中的所有数据。
     *
     * @param sessionId 会话唯一标识
     * @return 包含操作状态和 sessionId 的确认响应
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Map<String, String>> clearMemory(@PathVariable String sessionId) {
        memoryManager.clearSession(sessionId);
        return ResponseEntity.ok(Map.of("status", "cleared", "sessionId", sessionId));
    }
}
