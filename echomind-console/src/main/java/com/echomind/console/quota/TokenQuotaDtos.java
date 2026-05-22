package com.echomind.console.quota;

import java.time.Instant;
import java.util.List;

public final class TokenQuotaDtos {
    private TokenQuotaDtos() {
    }

    public record TokenQuotaView(
        String userId,
        String username,
        boolean active,
        Long dailyLimitTokens,
        Long monthlyLimitTokens,
        int warningThresholdPercent,
        TokenQuotaStatus status,
        long todayUsedTokens,
        long monthUsedTokens,
        long totalUsedTokens,
        long callCount,
        double dailyUsagePercent,
        double monthlyUsagePercent,
        boolean dailyExceeded,
        boolean monthlyExceeded,
        boolean dailyWarning,
        boolean monthlyWarning,
        Instant updatedAt
    ) {
    }

    public record TokenQuotaListResponse(List<TokenQuotaView> users) {
    }

    public record UpdateTokenQuotaRequest(
        Long dailyLimitTokens,
        Long monthlyLimitTokens,
        Integer warningThresholdPercent,
        TokenQuotaStatus status
    ) {
    }
}
