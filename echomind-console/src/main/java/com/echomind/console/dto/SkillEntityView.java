package com.echomind.console.dto;

import com.echomind.common.model.SkillState;
import com.echomind.skill.marketplace.SkillEntity;

import java.time.Instant;

/**
 * 技能市场持久化记录的接口视图。
 */
public record SkillEntityView(
    String id,
    String skillId,
    String name,
    String version,
    String description,
    String author,
    SkillState state,
    Instant createdAt,
    Instant updatedAt
) {
    public static SkillEntityView from(SkillEntity entity) {
        return new SkillEntityView(
            entity.getId(),
            entity.getName() + "@" + entity.getVersion(),
            entity.getName(),
            entity.getVersion(),
            entity.getDescription(),
            entity.getAuthor(),
            entity.getState(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }
}
