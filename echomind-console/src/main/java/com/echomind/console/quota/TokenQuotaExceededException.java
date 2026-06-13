package com.echomind.console.quota;

public class TokenQuotaExceededException extends RuntimeException {

    private final String userId;
    private final String scope;
    private final long usedTokens;
    private final long limitTokens;

    public TokenQuotaExceededException(String userId, String scope, long usedTokens, long limitTokens) {
        super("Token " + scopeLabel(scope) + "额度已超限（已用 " + usedTokens + " / 限额 " + limitTokens + "），"
            + "请稍后重试或联系管理员提升额度");
        this.userId = userId;
        this.scope = scope;
        this.usedTokens = usedTokens;
        this.limitTokens = limitTokens;
    }

    public static String scopeLabel(String scope) {
        return switch (scope) {
            case "daily" -> "每日";
            case "monthly" -> "每月";
            default -> scope;
        };
    }

    public String userId() {
        return userId;
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
