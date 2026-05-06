package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.common.model.AgentMessage;
import com.echomind.memory.MemoryManager;

/**
 * 记忆持久化阶段——管道第五阶段（order=50），负责将本轮对话的问答对保存到记忆系统。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>将用户消息以{@link AgentMessage#user(String)}格式存入记忆</li>
 *   <li>将最终响应以{@link AgentMessage#assistant(String)}格式存入记忆</li>
 * </ul>
 *
 * <p>这是管道的最后一个阶段（order=50），确保在下一轮对话中，{@code ContextEnrichStage}
 * 能通过{@link MemoryManager#getFullContext(String)}加载到完整的对话历史。</p>
 *
 * <p>数据流：</p>
 * <pre>
 * ContextEnrichStage（加载历史）→ ... → MemoryPersistStage（保存本轮）
 *     ↑                                        ↓
 *     └──────── 下一轮对话 ─────────────────────┘
 * </pre>
 *
 * <p>注意事项：</p>
 * <ul>
 *   <li>如果finalResponse为null（管道中途失败），只保存用户消息</li>
 *   <li>保存顺序：先用户消息，后助手响应</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see MemoryManager
 * @see AgentMessage
 */
public class MemoryPersistStage implements PipelineStage {

    /** 记忆管理器，提供会话记忆的持久化存储能力 */
    private final MemoryManager memoryManager;

    /**
     * 构造记忆持久化阶段。
     *
     * @param memoryManager 记忆管理器
     */
    public MemoryPersistStage(MemoryManager memoryManager) {
        this.memoryManager = memoryManager;
    }

    /**
     * @return 50 — 管道第五阶段（最后一阶段），在所有处理完成后执行
     */
    @Override
    public int order() { return 50; }

    /**
     * 将本轮对话持久化到记忆系统 —— 包含用户消息、Skill 调用结果和最终响应。
     *
     * <p>保存逻辑（try-finally 确保异常时用户消息也被保存）：</p>
     * <ol>
     *   <li>始终保存用户消息（finally 块保证）</li>
     *   <li>如有 Skill 调用结果，作为 tool 角色消息保存</li>
     *   <li>如有最终响应，作为 assistant 角色消息保存</li>
     * </ol>
     *
     * <p>此阶段不应修改上下文中的任何数据——它是一个纯副作用操作。</p>
     *
     * @param ctx 管道上下文，包含用户消息、Skill 结果和最终响应
     * @return 未修改的管道上下文（原样返回）
     */
    @Override
    public PipelineContext process(PipelineContext ctx) {
        try {
            // 保存 Skill 调用结果（作为 tool 角色消息）
            if (ctx.getSkillResults() != null && !ctx.getSkillResults().isEmpty()) {
                for (String skillResult : ctx.getSkillResults()) {
                    String toolCallId = skillResult.startsWith("[") && skillResult.contains("]:")
                        ? skillResult.substring(1, skillResult.indexOf("]:"))
                        : "skill";
                    memoryManager.addMessage(ctx.getSessionId(),
                        AgentMessage.tool(toolCallId, skillResult));
                }
            }
            // 保存助手最终响应
            if (ctx.getFinalResponse() != null) {
                memoryManager.addMessage(ctx.getSessionId(),
                    AgentMessage.assistant(ctx.getFinalResponse()));
            }
        } finally {
            // 用户消息无论是否异常都必须保存
            memoryManager.addMessage(ctx.getSessionId(),
                AgentMessage.user(ctx.getUserMessage()));
        }
        return ctx;
    }
}
