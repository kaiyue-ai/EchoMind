package com.echomind.agent.team.runtime;

import com.echomind.agent.team.model.PlannedStep;
import com.echomind.agent.team.state.TeamRiskLevel;

import java.util.List;
import java.util.Locale;

/**
 * Step 风险策略。
 *
 * <p>策略只读取 Planner 输出的风险等级和能力 metadata，不按具体 Agent、Skill 或任务标题硬编码。</p>
 */
public class TeamRiskPolicy {

    private static final List<String> HIGH_RISK_KEYWORDS = List.of(
        "风险", "安全", "应急", "医疗", "保险", "天气", "合规", "预算", "费用", "人员协调",
        "risk", "safety", "emergency", "medical", "insurance", "weather", "budget", "compliance",
         "紧急", "事故", "伤亡", "中毒", "食物中毒", "过敏",
    "法律", "合同", "纠纷", "投诉", "取消", "退款",
    "交通", "大巴", "事故", "超载", "疲劳驾驶",
    "场地安全", "消防", "逃生", "踩踏",
    "天气预警", "暴雨", "台风", "高温", "严寒", "雷暴",
    "取消险", "延误", "退款政策", "违约", "罚款"
    );

    public TeamRiskDecision decide(PlannedStep step) {
        if (step == null) {
            return new TeamRiskDecision(TeamRiskLevel.LOW, "默认低风险：Planner 未提供 Step 内容");
        }
        if (step.riskLevel() == TeamRiskLevel.HIGH) {
            return new TeamRiskDecision(TeamRiskLevel.HIGH,
                "Planner 建议高风险：" + blankToDefault(step.riskReason(), "需要 StepReviewer 重点校验"));
        }
        for (String tag : step.requiredCapabilities()) {
            String normalized = normalize(tag);
            if (normalized.equals("risk:high") || normalized.equals("review-required")) {
                return new TeamRiskDecision(TeamRiskLevel.HIGH,
                    "能力标签要求 StepReviewer 重点校验：" + normalized);
            }
        }
        String content = normalize(step.title() + " " + step.description() + " " + step.acceptanceCriteria()
            + " " + String.join(" ", step.requiredCapabilities()));
        for (String keyword : HIGH_RISK_KEYWORDS) {
            if (content.contains(normalize(keyword))) {
                return new TeamRiskDecision(TeamRiskLevel.HIGH,
                    "命中风险关键词「" + keyword + "」，需要 StepReviewer 重点校验");
            }
        }
        return new TeamRiskDecision(TeamRiskLevel.LOW, "未命中高风险规则，按低风险常规校验");
    }

    public TeamRiskLevel resolve(PlannedStep step) {
        return decide(step).level();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
