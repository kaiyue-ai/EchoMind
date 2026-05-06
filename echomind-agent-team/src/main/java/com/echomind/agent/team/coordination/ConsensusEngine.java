package com.echomind.agent.team.coordination;

import java.util.ArrayList;
import java.util.List;

/**
 * 共识引擎 —— 评估多个 Agent 的执行结果是否达成一致。
 *
 * <p>当多个 Agent 对同一任务产生不同结果时，共识引擎负责判断：
 * 是否所有结果均可接受，还是需要修订或存在冲突。
 *
 * <p>当前实现使用简单的规则判断：
 * <ul>
 *   <li>空结果集 → 冲突（CONFLICT）</li>
 *   <li>单个结果 → 直接通过（APPROVED）</li>
 *   <li>多个结果：所有结果均不以 "[Error]" 开头 → 通过（APPROVED）</li>
 *   <li>部分结果正常，部分为错误 → 需修订（REVISION_NEEDED）</li>
 *   <li>所有结果均为错误 → 冲突（CONFLICT）</li>
 * </ul>
 *
 * <p>设计要点：当前版本 criteriaNames 参数预留用于未来基于自定义标准的共识判断。
 */
public class ConsensusEngine {

    /**
     * 共识结果枚举。
     * <ul>
     *   <li><b>APPROVED</b> —— 所有结果均通过，可采纳</li>
     *   <li><b>REVISION_NEEDED</b> —— 部分结果有问题，需重新执行</li>
     *   <li><b>CONFLICT</b> —— 所有结果均有问题，存在严重冲突</li>
     * </ul>
     */
    public enum ConsensusResult {
        /** 通过 —— 所有结果可采纳 */
        APPROVED,
        /** 需修订 —— 部分结果有问题 */
        REVISION_NEEDED,
        /** 冲突 —— 所有结果均不可用 */
        CONFLICT
    }

    /**
     * 评估多个执行结果是否达成共识。
     *
     * <p>判断规则基于结果是否以 "[Error]" 前缀标记为错误。
     *
     * @param results       待评估的结果列表
     * @param criteriaNames 评估标准名称（当前版本预留，未使用）
     * @return 共识评估结果（APPROVED / REVISION_NEEDED / CONFLICT）
     */
    public ConsensusResult evaluate(List<String> results, List<String> criteriaNames) {
        if (results.isEmpty()) return ConsensusResult.CONFLICT;
        if (results.size() == 1) return ConsensusResult.APPROVED;

        // 简单的共识判断：检查是否所有结果均为非错误
        boolean allOk = results.stream().allMatch(r -> r != null && !r.startsWith("[Error]"));
        if (allOk) return ConsensusResult.APPROVED;

        boolean anyOk = results.stream().anyMatch(r -> r != null && !r.startsWith("[Error]"));
        return anyOk ? ConsensusResult.REVISION_NEEDED : ConsensusResult.CONFLICT;
    }
}
