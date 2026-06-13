package com.echomind.console.budget;

public class ProviderTokenBudgetExceededException extends RuntimeException {

    private final String providerId;
    private final String scope;
    private final long usedTokens;
    private final long limitTokens;

    public ProviderTokenBudgetExceededException(String providerId, String scope, long usedTokens, long limitTokens) {
        super("模型服务预算不足，" + scopeLabel(scope) + "预算已耗尽（已用 " + usedTokens + " / 限额 " + limitTokens
            + "），请稍后重试或切换模型");
        this.providerId = providerId;
        this.scope = scope;
        this.usedTokens = usedTokens;
        this.limitTokens = limitTokens;
    }

    public static String scopeLabel(String scope) {
        return switch (scope) {
            case "daily" -> "每日";
            case "weekly" -> "每周";
            case "monthly" -> "每月";
            default -> scope;
        };
    }

    public String providerId() {
        return providerId;
    }

    public String scope() {
        return scope;
    }

    public long usedTokens() {
        return usedTokens;
    }

    public long limitTokens() {
        return limitTokens;
    }
}
