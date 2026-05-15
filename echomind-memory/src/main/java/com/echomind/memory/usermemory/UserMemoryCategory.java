package com.echomind.memory.usermemory;

import java.util.Locale;

/** 用户画像分类。 */
public enum UserMemoryCategory {
    PERSONA,
    BACKGROUND,
    PREFERENCE,
    KNOWLEDGE,
    INTEREST;

    public static UserMemoryCategory from(String value) {
        if (value == null || value.isBlank()) {
            return INTEREST;
        }
        try {
            return UserMemoryCategory.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return INTEREST;
        }
    }

    public String storageValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String displayName() {
        return switch (this) {
            case PERSONA -> "画像";
            case BACKGROUND -> "背景";
            case PREFERENCE -> "偏好";
            case KNOWLEDGE -> "知识";
            case INTEREST -> "关注";
        };
    }
}
