package com.echomind.console.controller.rest;

import com.echomind.console.dto.AgentExecuteRequest;
import com.echomind.console.dto.AgentSaveRequest;
import com.echomind.console.dto.AgentView;
import com.echomind.console.service.AgentApplicationService;
import com.echomind.console.service.AgentKnowledgeApplicationService;
import com.echomind.memory.knowledge.AgentKnowledgeDocument;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

/**
 * Agent 管理控制器 —— 提供 Agent 的创建、查询与执行 API。
 *
 * <p>Agent 是 EchoMind 平台的核心执行单元，每个 Agent 拥有独立的：
 * <ul>
 *   <li><b>系统提示词</b>：定义 Agent 的角色和行为</li>
 *   <li><b>模型绑定</b>：指定使用的 LLM 模型</li>
 *   <li><b>技能集合</b>：可调用的技能列表</li>
 * </ul>
 *
 * <p>本控制器提供：
 * <ol>
 *   <li><b>列表</b>：查看所有已创建的 Agent</li>
 *   <li><b>创建</b>：使用 {@link AgentConfig} 配置创建新的 Agent 实例</li>
 *   <li><b>执行</b>：向指定 Agent 提交任务消息，委托执行管线处理</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/agents")
@RequiredArgsConstructor
public class AgentController {

    /** Agent应用服务，收口HTTP层之外的校验、持久化和运行时同步逻辑。 */
    private final AgentApplicationService agentService;
    /** Agent 知识库应用服务，收口文件校验和 Memory 模块调用。 */
    private final AgentKnowledgeApplicationService knowledgeService;

    /**
     * 列出所有 Agent。
     *
     * @return 当前系统中所有 Agent 的前端展示信息
     */
    @GetMapping
    public ResponseEntity<List<AgentView>> listAgents() {
        return ResponseEntity.ok(agentService.listAgents());
    }

    /**
     * 创建新的 Agent。
     *
     * <p>根据提供的配置（agentId、名称、系统提示词、模型 ID、技能列表）
     * 创建并注册一个新的 Agent 实例。
     *
     * @param config Agent 配置对象
     * @return 创建成功的 Agent 展示信息
     */
    @PostMapping
    public ResponseEntity<AgentView> create(@RequestBody AgentSaveRequest request) {
        return ResponseEntity.ok(agentService.createOrUpdate(request));
    }

    /**
     * 向指定 Agent 提交执行任务。
     *
     * <p>将用户消息发送给指定 Agent，经过执行管线（上下文增强 →
     * 工具解析 → 技能调用 → 结果聚合 → 记忆持久化）后返回结果。
     *
     * @param agentId 目标 Agent 标识
     * @param body    请求体，包含：
     *                <ul>
     *                  <li>{@code sessionId} —— （可选）会话标识，默认自动生成 UUID</li>
     *                  <li>{@code message} —— （必填）任务消息</li>
     *                </ul>
     * @return 包含 sessionId 和最终回复的响应
     */
    @PostMapping("/{agentId}/execute")
    public ResponseEntity<Map<String, Object>> execute(
            @PathVariable String agentId, @RequestBody AgentExecuteRequest request) {
        return ResponseEntity.ok(agentService.execute(agentId, request));
    }

    /** 查询指定 Agent 的知识库文档列表。 */
    @GetMapping("/{agentId}/knowledge")
    public ResponseEntity<List<AgentKnowledgeDocument>> listKnowledge(@PathVariable String agentId) {
        return ResponseEntity.ok(knowledgeService.listDocuments(agentId));
    }

    /** 上传 txt/pdf 到指定 Agent 的私有知识库。 */
    @PostMapping("/{agentId}/knowledge")
    public ResponseEntity<AgentKnowledgeDocument> uploadKnowledge(
            @PathVariable String agentId, @RequestParam("file") MultipartFile file) throws IOException {
        return ResponseEntity.ok(knowledgeService.upload(agentId, file));
    }

    /** 删除指定 Agent 的一份知识库文档。 */
    @DeleteMapping("/{agentId}/knowledge/{documentId}")
    public ResponseEntity<Map<String, Object>> deleteKnowledge(
            @PathVariable String agentId, @PathVariable Long documentId) {
        knowledgeService.deleteDocument(agentId, documentId);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    /**
     * 删除指定 Agent（运行时 + 持久化）。
     *
     * @param agentId Agent 标识
     * @return 204 No Content
     */
    @DeleteMapping("/{agentId}")
    public ResponseEntity<Void> deleteAgent(@PathVariable String agentId) {
        agentService.deleteAgent(agentId);
        return ResponseEntity.noContent().build();
    }

}
