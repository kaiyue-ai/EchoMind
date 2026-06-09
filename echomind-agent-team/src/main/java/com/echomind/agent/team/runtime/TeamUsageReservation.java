package com.echomind.agent.team.runtime;

import com.echomind.agent.pipeline.PipelineContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Team 单次内部模型调用的资源预留结果。
 *
 * <p>Team 运行时不关心 Redis 或预算表细节，只把入口层已经冻结的 reservation id
 * 透传给 PipelineContext，后续由统一用量结算逻辑按真实 token 消耗结算或释放。</p>
 */
public record TeamUsageReservation(
    List<String> userReservationIds,
    List<String> providerReservationIds
) {

    public static final TeamUsageReservation EMPTY = new TeamUsageReservation(List.of(), List.of());

    public TeamUsageReservation {
        userReservationIds = userReservationIds == null ? List.of() : List.copyOf(userReservationIds);
        providerReservationIds = providerReservationIds == null ? List.of() : List.copyOf(providerReservationIds);
    }

    public List<String> allReservationIds() {
        if (userReservationIds.isEmpty()) {
            return providerReservationIds;
        }
        if (providerReservationIds.isEmpty()) {
            return userReservationIds;
        }
        List<String> all = new ArrayList<>(userReservationIds.size() + providerReservationIds.size());
        all.addAll(userReservationIds);
        all.addAll(providerReservationIds);
        return List.copyOf(all);
    }

    public Map<String, Object> pipelineAttributes() {
        if (userReservationIds.isEmpty() && providerReservationIds.isEmpty()) {
            return Map.of();
        }
        if (userReservationIds.isEmpty()) {
            return Map.of(PipelineContext.ATTR_PROVIDER_TOKEN_RESERVATION_IDS, providerReservationIds);
        }
        if (providerReservationIds.isEmpty()) {
            return Map.of(PipelineContext.ATTR_USER_TOKEN_RESERVATION_IDS, userReservationIds);
        }
        return Map.of(
            PipelineContext.ATTR_USER_TOKEN_RESERVATION_IDS, userReservationIds,
            PipelineContext.ATTR_PROVIDER_TOKEN_RESERVATION_IDS, providerReservationIds
        );
    }

    public void attachTo(PipelineContext ctx) {
        if (ctx == null) {
            return;
        }
        if (!userReservationIds.isEmpty()) {
            ctx.getAttributes().putIfAbsent(PipelineContext.ATTR_USER_TOKEN_RESERVATION_IDS, userReservationIds);
        }
        if (!providerReservationIds.isEmpty()) {
            ctx.getAttributes().putIfAbsent(PipelineContext.ATTR_PROVIDER_TOKEN_RESERVATION_IDS,
                providerReservationIds);
        }
    }
}
