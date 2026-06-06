package com.echomind.agent.team.runtime;

import com.echomind.agent.pipeline.PipelineContext;

import java.util.List;

/**
 * Team 运行时的轻量用量治理端口。
 *
 * <p>agent-team 不依赖 console 持久化实现；console 负责把内部 LLM 调用写入现有
 * {@code echomind_ai_call_usage} 并执行同一套用户配额。</p>
 */
public interface TeamUsageRecorder {

    TeamUsageRecorder NOOP = new TeamUsageRecorder() {
    };

    default void assertAllowed(String userId, String agentId, String sessionId) {
    }

    default List<String> reserveUserQuota(String userId, String agentId, String sessionId) {
        return List.of();
    }

    default void record(String operation, String userId, String agentId, String sessionId,
                        PipelineContext ctx, long startedNanos, boolean error, String errorMessage) {
    }

    default void releaseReservations(List<String> reservationIds) {
    }
}
