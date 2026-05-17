package com.echomind.memory.persistence;

import java.io.Serializable;
import java.util.Objects;

/** MySQL 会话主表复合主键：同一个前端 sessionId 在不同用户下互不冲突。 */
public class ChatSessionId implements Serializable {

    private String userId;
    private String sessionId;

    public ChatSessionId() {
    }

    public ChatSessionId(String userId, String sessionId) {
        this.userId = userId;
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ChatSessionId that)) {
            return false;
        }
        return Objects.equals(userId, that.userId) && Objects.equals(sessionId, that.sessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, sessionId);
    }
}
