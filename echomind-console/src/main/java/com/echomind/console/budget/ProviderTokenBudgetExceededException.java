package com.echomind.console.budget;

public class ProviderTokenBudgetExceededException extends RuntimeException {

    private final String providerId;
    private final String scope;
    private final long usedTokens;
    private final long limitTokens;

    public ProviderTokenBudgetExceededException(String providerId, String scope, long usedTokens, long limitTokens) {
        super("Provider token budget exceeded: provider=" + providerId
            + " scope=" + scope + " used=" + usedTokens + " limit=" + limitTokens);
        this.providerId = providerId;
        this.scope = scope;
        this.usedTokens = usedTokens;
        this.limitTokens = limitTokens;
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
