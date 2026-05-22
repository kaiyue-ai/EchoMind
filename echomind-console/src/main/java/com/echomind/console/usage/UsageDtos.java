package com.echomind.console.usage;

import java.time.Instant;
import java.util.List;

public final class UsageDtos {
    private UsageDtos() {
    }

    public record TokenTotals(long promptTokens, long completionTokens, long totalTokens, long callCount) {
    }

    public record UsageSummary(TokenTotals totals) {
    }

    public record ClientUserUsage(
        String userId,
        String username,
        boolean active,
        Instant createdAt,
        TokenTotals totals
    ) {
    }

    public record ClientUserListResponse(TokenTotals totals, List<ClientUserUsage> users) {
    }

    public record UserCallsResponse(String userId, String username, TokenTotals totals, List<CallUsage> calls) {
    }

    public record AllCallsResponse(TokenTotals totals, List<CallUsage> calls) {
    }

    public record UserModelTokenUsage(
        String userId,
        String username,
        boolean active,
        String modelId,
        TokenTotals totals,
        long averageDurationMs
    ) {
    }

    public record UserModelTokenResponse(TokenTotals totals, List<UserModelTokenUsage> rows) {
    }

    public record CallUsage(
        String id,
        String traceId,
        String userId,
        String username,
        String operation,
        String agentId,
        String sessionId,
        String modelId,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        String usageSource,
        long durationMs,
        String status,
        String errorMessage,
        Instant createdAt
    ) {
    }
}
