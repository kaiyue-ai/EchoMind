package com.echomind.memory.usermemory;

import java.util.Locale;

/** 会话事件分类。 */
public enum UserMemoryCategory {
    EPISODE,    // 事件
    CORRECTION, // 纠正
    PREFERENCE, // 偏好
    KNOWLEDGE;  // 知识

    public static UserMemoryCategory from(String value) {
        if (value == null || value.isBlank()) {
            return EPISODE;
        }
        try {
            return UserMemoryCategory.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return EPISODE;
        }
    }

    public String storageValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String displayName() {
        return switch (this) {
            case EPISODE -> "事件";
            case CORRECTION -> "纠正";
            case PREFERENCE -> "偏好";
            case KNOWLEDGE -> "知识";
        };
    }
}
