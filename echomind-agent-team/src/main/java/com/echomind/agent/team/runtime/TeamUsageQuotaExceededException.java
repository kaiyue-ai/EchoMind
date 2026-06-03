package com.echomind.agent.team.runtime;

/** Team 内部模型调用命中用户 token 配额上限。 */
public class TeamUsageQuotaExceededException extends RuntimeException {

    public TeamUsageQuotaExceededException(String message, Throwable cause) {
        super(message, cause);
    }
}
