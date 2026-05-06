package com.echomind.agent.team.decision;

import com.echomind.agent.team.AgentTeam;

import java.util.List;

/**
 * 决策上下文 —— 传递给 {@link DynamicDecisionEngine} 的完整任务状态快照。
 *
 * <p>包含 LLM 做出明智决策所需的所有信息：
 * <ul>
 *   <li><b>会话标识</b>（sessionId）：用于模型路由和上下文关联</li>
 *   <li><b>当前任务</b>（task）：团队正在执行的原始任务描述</li>
 *   <li><b>已完成步骤</b>（completedSteps）：已成功执行的子任务列表</li>
 *   <li><b>待办步骤</b>（pendingSteps）：尚未执行的子任务列表</li>
 *   <li><b>可用角色</b>（availableRoles）：当前团队配置的角色集合</li>
 *   <li><b>最近消息</b>（recentMessages）：团队消息总线中的近期通信记录</li>
 * </ul>
 *
 * @param sessionId       会话唯一标识
 * @param task            当前任务描述
 * @param completedSteps  已完成的步骤列表
 * @param pendingSteps    待办的步骤列表
 * @param availableRoles  当前可用的团队角色
 * @param recentMessages  最近的团队消息记录
 */
public record DecisionContext(
    String sessionId,
    String task,
    List<String> completedSteps,
    List<String> pendingSteps,
    List<AgentTeam.TeamRole> availableRoles,
    List<String> recentMessages
) {}
