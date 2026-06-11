package com.echomind.console.quota;

import java.time.Instant;
import java.util.List;

public final class TokenQuotaDtos {
    private TokenQuotaDtos() {
    }

    public record TokenQuotaView(
        String userId, // 用户名
        String username,
        boolean active, // 是否激活
        Long dailyLimitTokens, // 每日tokens限制
        Long monthlyLimitTokens, // 每月tokens限制
        TokenQuotaStatus status, // 状态
        long todayUsedTokens, // 今日已用tokens
        long monthUsedTokens, // 本月已用tokens
        long totalUsedTokens, // 总已用tokens
        long callCount, // 调用次数
        double dailyUsagePercent, // 今日使用百分比
        double monthlyUsagePercent, // 本月使用百分比
        boolean dailyExceeded, // 是否超过每日限制
        boolean monthlyExceeded, // 是否超过每月限制

        Instant updatedAt // 更新时间
    ) {
    }

    public record TokenQuotaListResponse(List<TokenQuotaView> users) {
    }

    public record UpdateTokenQuotaRequest(
        Long dailyLimitTokens,
        Long monthlyLimitTokens,
        TokenQuotaStatus status
    ) {
    }
}
