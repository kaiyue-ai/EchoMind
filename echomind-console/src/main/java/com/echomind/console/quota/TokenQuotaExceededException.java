package com.echomind.console.quota;

public class TokenQuotaExceededException extends RuntimeException {

    private final String userId;
    private final String scope;
    private final long usedTokens;
    private final long limitTokens;

    public TokenQuotaExceededException(String userId, String scope, long usedTokens, long limitTokens) {
        super("Token quota exceeded: " + scope + " used=" + usedTokens + " limit=" + limitTokens);
        this.userId = userId;
        this.scope = scope;
        this.usedTokens = usedTokens;
        this.limitTokens = limitTokens;
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
