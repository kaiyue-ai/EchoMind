package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.common.model.AgentMessage;
import com.echomind.memory.MemoryManager;

/**
 * 上下文丰富阶段——管道第一阶段（order=10），负责加载会话历史并组装完整的消息上下文。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>从{@link MemoryManager}加载当前会话的历史消息（短期记忆窗口 + 长期持久化存储）</li>
 *   <li>将历史消息填充到{@link PipelineContext#getMessages()}</li>
 *   <li>将当前用户消息作为最新一条消息追加到列表末尾</li>
 * </ul>
 *
 * <p>设计意图：</p>
 * <ul>
 *   <li>确保LLM在生成响应时能访问完整的对话历史</li>
 *   <li>为后续阶段（如SkillInvocationStage和ResultAggregationStage）提供上下文基础</li>
 *   <li>作为管道的第一步（order=10），为所有后续阶段奠定数据基础</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see MemoryManager
 */
public class ContextEnrichStage implements PipelineStage {

    /** 记忆管理器，提供会话历史的加载和保存能力 */
    private final MemoryManager memoryManager;

    /**
     * 构造上下文丰富阶段。
     *
     * @param memoryManager 记忆管理器，用于加载会话历史
     */
    public ContextEnrichStage(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    /**
     * @return 10 — 管道第一阶段，最先执行
     */
    @Override
    public int order() { return 10; }

    /**
     * 加载会话历史消息并添加当前用户消息到上下文中。
     *
     * <p>处理逻辑：</p>
     * <ol>
     *   <li>通过{@link MemoryManager#getFullContext(String)}获取完整会话历史</li>
     *   <li>将所有历史消息添加到上下文的messages列表</li>
     *   <li>将当前用户消息包装为{@link AgentMessage#user(String)}追加到列表</li>
     * </ol>
     *
     * @param ctx 管道上下文
     * @return 包含完整消息历史的管道上下文
     */
    @Override
    public PipelineContext process(PipelineContext ctx) {
        var history = memoryManager.getFullContext(ctx.getSessionId());
        ctx.getMessages().addAll(history);
        ctx.getMessages().add(AgentMessage.user(ctx.getUserMessage()));
        return ctx;
    }
}
