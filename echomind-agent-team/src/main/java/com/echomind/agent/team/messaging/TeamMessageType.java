package com.echomind.agent.team.messaging;

/**
 * 团队消息类型枚举 —— 定义 Agent Team 通信中的消息类别。
 *
 * <p>四种消息类型覆盖了团队协作的关键通信模式：
 * <ul>
 *   <li><b>TASK_ASSIGN</b> —— 任务分配：协调器向 Agent 分派子任务</li>
 *   <li><b>RESULT_REPORT</b> —— 结果报告：Agent 向协调器回报执行结果</li>
 *   <li><b>DISPUTE</b> —— 争议提交：Agent 之间对结果有不同意见时使用</li>
 *   <li><b>CONSENSUS</b> —— 达成共识：各方就结论达成一致后广播</li>
 * </ul>
 */
public enum TeamMessageType {
    /** 任务分配 —— 协调器向 Agent 分派子任务 */
    TASK_ASSIGN,
    /** 结果报告 —— Agent 向协调器回报执行结果 */
    RESULT_REPORT,
    /** 争议提交 —— Agent 之间对结果存在分歧 */
    DISPUTE,
    /** 达成共识 —— 所有相关方就结论达成一致 */
    CONSENSUS
}
