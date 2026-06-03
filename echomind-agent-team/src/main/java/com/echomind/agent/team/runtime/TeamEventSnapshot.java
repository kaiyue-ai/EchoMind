package com.echomind.agent.team.runtime;

import com.echomind.agent.team.state.TeamEventType;
import com.echomind.agent.team.state.TeamRole;

import java.time.Instant;

/**
 * Team 事件流的接口快照。
 */
public record TeamEventSnapshot(
    Long id, //事件id
    String runId, // 所属任务id
    String stepId, //步骤id
    TeamEventType type, // 事件类型
    TeamRole actorRole, // 事件触发角色
    String actorAgentId, // 事件触发执行器id
    String message, // 事件消息
    String payloadJson, // 事件负载json
    Instant createdAt // 事件创建时间
) {}
