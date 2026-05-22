package com.echomind.console.dashboard;

import com.echomind.console.usage.UsageDtos.CallUsage;
import com.echomind.console.usage.UsageDtos.TokenTotals;

import java.time.LocalDate;
import java.util.List;

public final class AdminDashboardDtos {

    private AdminDashboardDtos() {
    }

    public record DashboardResponse(
        DashboardSummary summary,
        List<ModelDistribution> modelDistribution,
        List<TokenTrendPoint> tokenTrend,
        List<CallUsage> recentCalls
    ) {
    }

    public record DashboardSummary(
        TokenTotals totalTokens,
        TokenTotals rangeTokens,
        TokenTotals todayTokens,
        long totalUsers,
        long activeUsers,
        long disabledUsers,
        long rangeRequests,
        long todayRequests,
        long totalRequests,
        double averageDurationMs,
        double rangeAverageDurationMs,
        double rangeErrorRatePercent,
        long rangeSensitiveEvents,
        long rangeAlertEvents
    ) {
    }

    public record ModelDistribution(
        String modelId,
        long callCount,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        double averageDurationMs
    ) {
    }

    public record TokenTrendPoint(
        LocalDate date,
        long promptTokens,
        long completionTokens,
        long totalTokens,
        long callCount,
        double averageDurationMs
    ) {
    }
}
