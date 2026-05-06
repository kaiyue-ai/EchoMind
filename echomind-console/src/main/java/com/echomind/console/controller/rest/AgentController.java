package com.echomind.console.controller.rest;

import com.echomind.agent.Agent;
import com.echomind.agent.AgentConfig;
import com.echomind.agent.AgentFactory;
import com.echomind.agent.orchestration.AgentOrchestrator;
import com.echomind.agent.pipeline.PipelineContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

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
public class AgentController {

    /** Agent 工厂，负责根据配置创建和管理 Agent 实例 */
    private final AgentFactory factory;
    /** Agent 编排器，驱动完整的执行管线 */
    private final AgentOrchestrator orchestrator;

    /**
     * 构造 Agent 管理控制器。
     *
     * @param factory      Agent 工厂
     * @param orchestrator Agent 编排器
     */
    public AgentController(AgentFactory factory, AgentOrchestrator orchestrator) {
        this.factory = factory;
        this.orchestrator = orchestrator;
    }

    /**
     * 列出所有 Agent。
     *
     * @return 当前系统中所有 Agent 实例的集合
     */
    @GetMapping
    public ResponseEntity<Collection<Agent>> listAgents() {
        return ResponseEntity.ok(factory.allAgents().values());
    }

    /**
     * 创建新的 Agent。
     *
     * <p>根据提供的配置（agentId、名称、系统提示词、模型 ID、技能列表）
     * 创建并注册一个新的 Agent 实例。
     *
     * @param config Agent 配置对象
     * @return 创建成功的 Agent 实例
     */
    @PostMapping
    public ResponseEntity<Agent> create(@RequestBody AgentConfig config) {
        return ResponseEntity.ok(factory.create(config));
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
            @PathVariable String agentId, @RequestBody Map<String, String> body) {
        String sessionId = body.getOrDefault("sessionId", UUID.randomUUID().toString());
        String message = body.get("message");
        PipelineContext result = orchestrator.execute(agentId, sessionId, message);
        return ResponseEntity.ok(Map.of(
            "sessionId", result.getSessionId(),
            "response", result.getFinalResponse()
        ));
    }
}
