package com.echomind.console.controller.rest;

import com.echomind.common.model.AgentMessage;
import com.echomind.console.service.MemoryApplicationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

/**
 * 记忆管理控制器。
 *
 * <p>这里的 id 代表 memoryKey，也就是一次对话的 sessionId。</p>
 */
@RestController
@RequestMapping("/api/memory")
@RequiredArgsConstructor
public class MemoryController {

    /** 记忆应用服务，统一读取和清理指定会话的持久化记忆。 */
    private final MemoryApplicationService memoryService;

    /** 查询指定对话的完整记忆。 */
    @GetMapping("/{sessionId}")
    public ResponseEntity<List<AgentMessage>> getMemory(@PathVariable String sessionId) {
        return ResponseEntity.ok(memoryService.getMemory(sessionId));
    }

    /** 清除指定对话的所有数据。 */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Map<String, String>> clearMemory(@PathVariable String sessionId) {
        return ResponseEntity.ok(memoryService.clearMemory(sessionId));
    }
}
