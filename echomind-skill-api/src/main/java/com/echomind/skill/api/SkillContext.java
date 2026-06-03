package com.echomind.skill.api;

import java.util.Map;

/**
 * <h2>技能执行上下文</h2>
 * 承载当前会话和智能体的运行时信息，在技能执行期间提供环境感知能力。
 *
 * <h3>用途</h3>
 * <p>
 * SkillContext 使技能能够在执行期间感知到"谁在调用"以及"在什么会话中调用"，
 * 从而实现以下能力：
 * </p>
 * <ul>
 *   <li><b>会话隔离</b> —— 通过 {@code sessionId} 区分不同用户的会话，避免数据混写</li>
 *   <li><b>智能体关联</b> —— 通过 {@code agentId} 了解是哪个智能体发起的调用，
 *       当多智能体协作时尤其重要</li>
 *   <li><b>状态携带</b> —— 通过 {@code sessionAttributes} 在多次技能调用之间传递
 *       会话级别的临时数据（如用户偏好、中间计算结果等）</li>
 * </ul>
 *
 * <h3>数据传递方向</h3>
 * <p>
 * SkillContext 由平台在构建 {@link SkillRequest} 时注入，从
 * {@code AgentOrchestrator} → {@code ExecutionPipeline} → {@code Skill.execute()}。
 * 技能可以读取其中的信息，但不应该修改上下文内容（虽然技术上 Map 是可变的）。
 * 如需存储技能执行过程中的状态，应放在 Skill 内部而非上下文 Map 中。
 * </p>
 *
 * @see SkillRequest#context() 包含此上下文的技能请求
 * @see com.echomind.agent.Agent 创建和管理智能体实例的实体
 * @see com.echomind.memory.MemoryManager 当前会话的记忆编排器
 */
public record SkillContext(
    /**
     * 当前会话的唯一标识符。
     * 每次用户发起一个新的对话时由平台分配，在整个对话生命周期内保持不变。
     * 技能可以使用此 ID 来隔离不同会话的数据或缓存。
     */
    String sessionId, // sessionId
    /**
     * 发起当前技能调用的智能体唯一标识符。
     * 在单智能体场景下，这就是当前唯一的 Agent ID；
     * 在多智能体协作场景下，可用于追踪调用链中的发起方。
     */
    String agentId, // 智能体的id
    /**
     * 会话级别的属性映射（键值对）。
     * 可以携带用户偏好设置、历史计算结果、认证 token 等会话范围内的临时数据。
     * 此 Map 是可变的，但技能应遵循只读约定（除非有明确的写入需求）。
     */
    Map<String, Object> sessionAttributes // 会话级别的属性映射
) {}
