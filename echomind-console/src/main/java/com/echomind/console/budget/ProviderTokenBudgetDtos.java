package com.echomind.console.budget;

import java.time.Instant;
import java.util.List;

public final class ProviderTokenBudgetDtos {
    private ProviderTokenBudgetDtos() {
    }

    public record ProviderTokenBudgetView(
        String providerId,
        Long dailyLimitTokens,
        Long weeklyLimitTokens,
        Long monthlyLimitTokens,
        int warningThresholdPercent,
        ProviderTokenBudgetStatus status,
        long todayUsedTokens,
        long weekUsedTokens,
        long monthUsedTokens,
        double dailyUsagePercent,
        double weeklyUsagePercent,
        double monthlyUsagePercent,
        boolean dailyExceeded,
        boolean weeklyExceeded,
        boolean monthlyExceeded,
        boolean dailyWarning,
        boolean weeklyWarning,
        boolean monthlyWarning,
        Instant updatedAt
    ) {
    }

    public record ProviderTokenBudgetListResponse(List<ProviderTokenBudgetView> budgets) {
    }

    public record UpdateProviderTokenBudgetsRequest(List<ProviderTokenBudgetView> budgets) {
    }
}
