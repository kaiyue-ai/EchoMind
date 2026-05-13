package com.echomind.console.dto;

import com.echomind.common.model.SkillState;
import com.echomind.skill.marketplace.SkillRepository;

import java.time.Instant;

/**
 * 技能市场持久化记录的接口视图。
 */
public record SkillRepositoryView(
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
    public static SkillRepositoryView from(SkillRepository entity) {
        return new SkillRepositoryView(
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
