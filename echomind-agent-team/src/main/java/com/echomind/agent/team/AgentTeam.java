package com.echomind.agent.team;

import com.echomind.agent.Agent;

import java.util.List;
import java.util.Map;

/**
 * Agent 团队 —— 组合多个 Agent 以完成复杂协作任务。
 *
 * <p>一个 Agent Team 由不同角色（{@link TeamRole}）的 Agent 组成，
 * 每个角色承担协作流程中的特定职责。典型配置：
 * <ul>
 *   <li><b>PLANNER（规划者）</b>：分解任务为子任务</li>
 *   <li><b>EXECUTOR（执行者）</b>：逐一执行子任务</li>
 *   <li><b>REVIEWER（审阅者）</b>：评估执行结果并生成最终输出</li>
 * </ul>
 *
 * <p>设计要点：
 * <ul>
 *   <li>角色-Agent 映射在构造时通过 {@link Map#copyOf} 进行防御性拷贝，
 *       防止外部修改内部状态。</li>
 *   <li>每个角色最多绑定一个 Agent（1:1 映射），但同一 Agent 可兼任多个角色。</li>
 *   <li>{@link #getAgent(TeamRole)} 返回 null 时，协调器将使用回退策略
 *       （如使用其他角色的 Agent 代替）。</li>
 * </ul>
 */
public class AgentTeam {

    /** 团队唯一标识 */
    private final String teamId;
    /** 团队显示名称 */
    private final String name;
    /** 角色到 Agent 的映射（不可变副本） */
    private final Map<TeamRole, Agent> roleAgents;

    /**
     * 创建 Agent 团队。
     *
     * @param teamId     团队唯一标识
     * @param name       团队显示名称
     * @param roleAgents 角色到 Agent 的映射（将被防御性拷贝）
     */
    public AgentTeam(String teamId, String name, Map<TeamRole, Agent> roleAgents) {
        this.teamId = teamId;
        this.name = name;
        this.roleAgents = Map.copyOf(roleAgents);
    }

    /** @return 团队唯一标识 */
    public String getTeamId() { return teamId; }
    /** @return 团队显示名称 */
    public String getName() { return name; }
    /**
     * 获取指定角色绑定的 Agent。
     * @param role 团队角色
     * @return 该角色对应的 Agent；若未绑定则返回 null
     */
    public Agent getAgent(TeamRole role) { return roleAgents.get(role); }
    /** @return 团队中已配置的角色列表（不可变） */
    public List<TeamRole> getRoles() { return List.copyOf(roleAgents.keySet()); }

    /**
     * 团队角色枚举 —— 定义 Agent Team 协作流程中的三种角色。
     *
     * <p>协作流程为 PLANNER → EXECUTOR → REVIEWER 的线性管线：
     * <ol>
     *   <li><b>PLANNER</b>：接收用户任务，将其分解为 2-4 个可执行的子任务</li>
     *   <li><b>EXECUTOR</b>：逐一执行每个子任务，产出中间结果</li>
     *   <li><b>REVIEWER</b>：审阅所有中间结果，合并生成最终输出</li>
     * </ol>
     */
    public enum TeamRole {
        /** 规划者 —— 负责任务分解 */
        PLANNER,
        /** 执行者 —— 负责子任务执行 */
        EXECUTOR,
        /** 审阅者 —— 负责结果评估与合并 */
        REVIEWER
    }
}
