package com.echomind.console.dto;

import com.echomind.common.model.SkillState;
import com.echomind.skill.api.SkillMetadata;
import com.echomind.skill.marketplace.SkillRepository;
import com.echomind.skill.registry.SkillRegistration;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 运行时 Skill 的接口视图。
 */
public record SkillView(
    String skillId,
    SkillMetadata metadata,
    SkillState state
) {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};
    private static final TypeReference<Map<String, List<String>>> ALIASES_TYPE = new TypeReference<>() {};

    public static SkillView from(SkillRegistration registration) {
        return new SkillView(
            registration.getSkillId(),
            registration.getMetadata(),
            registration.getState()
        );
    }

    public static SkillView fromMarketplace(SkillRepository entity) {
        SkillMetadata metadata = new SkillMetadata(
            entity.getName(),
            entity.getVersion(),
            entity.getDescription(),
            readJson(entity.getParameterSchemaJson(), MAP_TYPE, Map.of()),
            readJson(entity.getDependenciesJson(), STRING_LIST_TYPE, List.of()),
            entity.getAuthor(),
            readJson(entity.getTagsJson(), STRING_LIST_TYPE, List.of()),
            readJson(entity.getKeywordsJson(), STRING_LIST_TYPE, List.of()),
            readJson(entity.getAliasesJson(), ALIASES_TYPE, Map.of())
        );
        return new SkillView(
            metadata.skillId(),
            metadata,
            entity.getState() != null ? entity.getState() : SkillState.LOADED
        );
    }

    private static <T> T readJson(String json, TypeReference<T> type, T fallback) {
        if (json == null || json.isBlank()) {
            return fallback;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception ignored) {
            return fallback;
        }
    }
}
