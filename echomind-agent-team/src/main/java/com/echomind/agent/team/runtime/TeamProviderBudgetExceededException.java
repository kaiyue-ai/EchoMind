package com.echomind.agent.team.runtime;

/** Team 内部模型调用命中平台 Provider token 预算上限。 */
public class TeamProviderBudgetExceededException extends RuntimeException {

    public TeamProviderBudgetExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
