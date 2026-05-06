package com.echomind.agent.team.messaging;

/**
 * 团队消息 —— Agent Team 内部通信的消息单元。
 *
 * <p>通过 {@link TeamMessageBus} 在团队各角色之间传递的消息载体。
 * 消息包含发送者、接收者、消息类型和载荷内容。
 *
 * <p>典型使用场景：
 * <ul>
 *   <li>协调器广播任务分配（from="team-coordinator", to="all"）</li>
 *   <li>Agent 上报执行结果（to="team-coordinator"）</li>
 *   <li>Agent 发送协商/共识消息</li>
 * </ul>
 *
 * @param from    发送者标识（Agent ID 或 "team-coordinator"）
 * @param to      接收者标识（Agent ID 或 "all" 表示广播）
 * @param type    消息类型
 * @param payload 消息载荷内容
 */
public record TeamMessage(
    String from,
    String to,
    TeamMessageType type,
    String payload
) {}
