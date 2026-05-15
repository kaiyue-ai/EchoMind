package com.echomind.console.service;

import com.echomind.agent.AgentConfig;
import com.echomind.agent.AgentFactory;
import com.echomind.agent.orchestration.AgentOrchestrator;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.store.AgentPersistenceService;
import com.echomind.console.dto.AgentExecuteRequest;
import com.echomind.console.dto.AgentSaveRequest;
import com.echomind.console.dto.AgentView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Agent应用服务。
 *
 * <p>这一层是REST管理面和Agent运行时之间的边界：Controller只负责HTTP参数和响应，
 * 这里负责校验业务输入、写入MySQL事实来源，并把配置同步到运行时Agent索引。</p>
 *
 * <p>当前系统里有两类Agent数据结构：</p>
 * <ul>
 *   <li>MySQL中的Agent配置：持久化事实来源，重启后仍然存在。</li>
 *   <li>{@link AgentFactory}中的Agent实例：运行时索引，用于快速按agentId执行请求。</li>
 * </ul>
 *
 * <p>创建或更新Agent时必须先写MySQL，再刷新运行时索引；这样即使部署或重启，
 * 启动阶段也能从数据库恢复出完整Agent列表。</p>
 */
@Service
@RequiredArgsConstructor
public class AgentApplicationService {

    private final AgentFactory factory;
    private final AgentOrchestrator orchestrator;
    private final AgentPersistenceService persistenceService;

    /**
     * 列出当前运行时可用的全部Agent。
     *
     * <p>运行时Agent来自启动阶段的MySQL恢复以及本进程内新创建的Agent。
     * 返回DTO而不是直接返回Agent实例，是为了避免把执行管线等内部对象暴露给前端。</p>
     */
    public List<AgentView> listAgents() {
        return factory.allAgents().values().stream()
            .map(AgentView::from)
            .toList();
    }

    /**
     * 创建或覆盖一个Agent。
     *
     * <p>同一个agentId重复提交时，会覆盖MySQL中的配置，并用新配置刷新运行时Agent实例。
     * 这相当于一个轻量的upsert，方便前端保存编辑结果。</p>
     *
     * @param config 前端提交的Agent配置
     * @return 创建后的前端展示模型
     */
    public AgentView createOrUpdate(AgentConfig config) {
        normalizeAndValidate(config);
        persistenceService.save(config);
        return AgentView.from(factory.create(config));
    }

    /** 创建或覆盖一个Agent，接口层DTO会在这里转换成内部配置对象。 */
    public AgentView createOrUpdate(AgentSaveRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Agent配置不能为空");
        }
        return createOrUpdate(request.toConfig());
    }

    /**
     * 执行指定Agent。
     *
     * <p>这个入口保留给Agent管理页或调试工具使用；聊天页的主入口仍然走
     * {@link ChatApplicationService}，以便统一处理同步、异步和流式三种模式。</p>
     */
    public java.util.Map<String, Object> execute(String agentId, AgentExecuteRequest request) {
        String sessionId = request != null && request.sessionId() != null
            ? request.sessionId()
            : UUID.randomUUID().toString();
        String message = request == null ? null : request.message();
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message不能为空");
        }

        PipelineContext result = orchestrator.execute(agentId, sessionId, message);
        return java.util.Map.of(
            "sessionId", result.getSessionId(),
            "response", result.getFinalResponse()
        );
    }

    /**
     * 规范化并校验Agent配置。
     *
     * <p>这里不做复杂业务推断，只保证进入持久化层和运行时工厂的数据是完整的。
     * 模型是否真实可用、Skill是否已启用，会在实际执行阶段由模型路由和能力注册中心兜底。</p>
     */
    private void normalizeAndValidate(AgentConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("Agent配置不能为空");
        }
        if (config.getAgentId() == null || config.getAgentId().isBlank()) {
            throw new IllegalArgumentException("agentId不能为空");
        }
        if (config.getName() == null || config.getName().isBlank()) {
            throw new IllegalArgumentException("Agent名称不能为空");
        }
        if (config.getSystemPrompt() == null || config.getSystemPrompt().isBlank()) {
            throw new IllegalArgumentException("系统提示词不能为空");
        }
        if (config.getModelId() == null || config.getModelId().isBlank()) {
            throw new IllegalArgumentException("模型ID不能为空");
        }
        if (config.getSkillIds() == null) {
            config.setSkillIds(List.of());
        }
    }

    /**
     * 删除指定Agent（运行时 + 持久化）。
     *
     * @param agentId Agent标识
     * @throws IllegalArgumentException 如果Agent不存在
     */
    public void deleteAgent(String agentId) {
        if (agentId == null || agentId.isBlank()) {
            throw new IllegalArgumentException("agentId不能为空");
        }
        factory.remove(agentId);
        boolean deleted = persistenceService.delete(agentId);
        if (!deleted) {
            throw new IllegalArgumentException("Agent不存在: " + agentId);
        }
    }
}
