package com.echomind.console.dto;

import com.echomind.common.model.SkillState;
import com.echomind.skill.api.SkillMetadata;
import com.echomind.skill.registry.SkillRegistration;

/**
 * 运行时 Skill 的接口视图。
 */
public record SkillView(
    String skillId,
    SkillMetadata metadata,
    SkillState state
) {
    public static SkillView from(SkillRegistration registration) {
        return new SkillView(
            registration.getSkillId(),
            registration.getMetadata(),
            registration.getState()
        );
    }
}
