package com.echomind.agent.team.decision;

import com.echomind.agent.team.AgentTeam;
import com.echomind.llm.provider.ModelProvider;
import com.echomind.llm.router.DynamicModelRouter;
import com.echomind.llm.router.ModelSpec;
import com.echomind.llm.session.SessionContext;

import lombok.RequiredArgsConstructor;

/**
 * 动态决策引擎 —— 使用 LLM 决定 Agent Team 的下一步行动。
 *
 * <p>在团队协作过程中，当遇到不确定情况时（如执行结果有争议、
 * 需要澄清信息等），本引擎利用 LLM 的推理能力动态决定下一步：
 * 继续执行、请求帮助、报告结果、要求澄清、重试或中止。
 *
 * <p>工作流程：
 * <ol>
 *   <li>根据会话 ID 解析最适合的 LLM 模型</li>
 *   <li>构建包含当前任务、已完成/待办步骤、可用角色和最近消息的提示词</li>
 *   <li>调用 LLM 获取下一步行动建议</li>
 *   <li>解析 LLM 响应为 {@link TeamAction} 枚举值</li>
 * </ol>
 *
 * <p>设计要点：这是项目中的额外加分功能（bonus feature），
 * 通过 LLM 提供比简单规则更灵活的决策能力。
 */
@RequiredArgsConstructor
public class DynamicDecisionEngine {

    /** 动态模型路由器，根据会话选择最优模型 */
    private final DynamicModelRouter router;
    /** 模型提供商，用于调用 LLM API */
    private final ModelProvider provider;

    /**
     * 决定下一步团队行动。
     *
     * <p>构建包含完整上下文的提示词，调用 LLM 获取建议，
     * 然后将 LLM 响应解析为 {@link TeamAction}。
     *
     * @param ctx 决策上下文（当前任务状态和团队信息）
     * @return LLM 建议的下一步行动；解析失败时默认返回 CONTINUE
     */
    public TeamAction decideNextAction(DecisionContext ctx) {
        ModelSpec model = router.resolve(SessionContext.create(ctx.sessionId()));

        String prompt = """
            You are an agent team decision engine. Based on the current task context, decide the next action.

            Current task: %s
            Completed steps: %s
            Pending steps: %s
            Available roles: %s
            Recent messages: %s

            Respond with exactly one action from: CONTINUE, REQUEST_HELP, REPORT_RESULT, ASK_CLARIFICATION, RETRY, ABORT.
            """.formatted(
                ctx.task(),
                String.join(", ", ctx.completedSteps()),
                String.join(", ", ctx.pendingSteps()),
                ctx.availableRoles().stream().map(Enum::name).toList(),
                String.join("\n", ctx.recentMessages())
            );

        String response = provider.chat(model, "You are a decision engine.", prompt);
        return TeamAction.fromResponse(response);
    }

    /**
     * 团队行动枚举 —— LLM 可以建议的下一步行动类型。
     *
     * <p>六种行动覆盖了团队协作中的关键决策点：
     * <ul>
     *   <li><b>CONTINUE</b> —— 继续执行当前任务流程</li>
     *   <li><b>REQUEST_HELP</b> —— 请求其他 Agent 或角色协助</li>
     *   <li><b>REPORT_RESULT</b> —— 报告当前结果给协调器</li>
     *   <li><b>ASK_CLARIFICATION</b> —— 向用户请求任务澄清</li>
     *   <li><b>RETRY</b> —— 重新尝试失败的操作</li>
     *   <li><b>ABORT</b> —— 中止执行，无法继续</li>
     * </ul>
     */
    public enum TeamAction {
        /** 继续执行 */
        CONTINUE,
        /** 请求帮助 */
        REQUEST_HELP,
        /** 报告结果 */
        REPORT_RESULT,
        /** 请求澄清 */
        ASK_CLARIFICATION,
        /** 重新尝试 */
        RETRY,
        /** 中止执行 */
        ABORT;

        /**
         * 从 LLM 的文本响应中解析出团队行动。
         *
         * <p>解析策略：检查响应文本是否包含某个枚举值的名称（大小写不敏感）。
         * 若无匹配或响应为 null，默认返回 CONTINUE。
         *
         * @param response LLM 的原始文本响应
         * @return 解析出的团队行动；默认返回 CONTINUE
         */
        public static TeamAction fromResponse(String response) {
            if (response == null) return CONTINUE;
            String upper = response.trim().toUpperCase();
            for (TeamAction action : values()) {
                if (upper.contains(action.name())) return action;
            }
            return CONTINUE;
        }
    }
}
