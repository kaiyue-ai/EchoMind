package com.echomind.common.model;

import java.time.Instant;

/**
 * 记忆摘要 DTO。
 *
 * <p>字段名仍沿用 sessionId，但在当前实现里它承载的是 memoryKey，
 * 通常就是 agentId。</p>
 */
// 就是展示在前端的历史对话列表的实体对象
public record SessionSummary(
    String sessionId,
    int messageCount,
    String lastMessage,
    Instant lastActivity
) {}
