package com.echomind.boot.autoconfigure;

import com.echomind.agent.AgentConfig;
import com.echomind.boot.properties.EchoMindProperties;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/**
 * Applies configured startup seed and migration rules for persisted Agents.
 */
final class AgentBootstrapPolicy {

    private final EchoMindProperties.AgentBootstrap config;

    AgentBootstrapPolicy(EchoMindProperties.AgentBootstrap config) {
        this.config = config == null ? new EchoMindProperties.AgentBootstrap() : config;
    }

    boolean mergeConfiguredDefaults(AgentConfig persisted, EchoMindProperties.AgentDef configuredAgent) {
        if (persisted == null || configuredAgent == null
            || !Objects.equals(configuredAgent.getAgentId(), persisted.getAgentId())) {
            return false;
        }
        boolean changed = mergeDefaultSkills(persisted, configuredAgent.getSkillIds());
        String migratedModelId = migratedModelId(persisted.getModelId(), configuredAgent.getModelId());
        if (migratedModelId != null) {
            persisted.setModelId(migratedModelId);
            changed = true;
        }
        return changed;
    }

    AgentConfig fallbackAgent() {
        return fromConfiguredAgent(config.getFallbackAgent());
    }

    AgentConfig fromConfiguredAgent(EchoMindProperties.AgentDef def) {
        AgentConfig agent = new AgentConfig();
        if (def == null) {
            return agent;
        }
        agent.setAgentId(def.getAgentId());
        agent.setName(def.getName());
        agent.setSystemPrompt(def.getSystemPrompt());
        agent.setModelId(def.getModelId());
        agent.setSkillIds(def.getSkillIds());
        return agent;
    }

    private boolean mergeDefaultSkills(AgentConfig persisted, List<String> configuredSkillIds) {
        if (configuredSkillIds == null || configuredSkillIds.isEmpty()) {
            return false;
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>(
            persisted.getSkillIds() == null ? List.of() : persisted.getSkillIds());
        boolean changed = false;
        for (String skillId : defaultSkillsToMerge(configuredSkillIds)) {
            if (merged.add(skillId)) {
                changed = true;
            }
        }
        if (changed) {
            persisted.setSkillIds(List.copyOf(merged));
        }
        return changed;
    }

    private List<String> defaultSkillsToMerge(List<String> configuredSkillIds) {
        List<String> mergeCandidates = config.getDefaultSkillMergeIds() == null
            ? List.of()
            : config.getDefaultSkillMergeIds();
        return mergeCandidates.stream()
            .filter(configuredSkillIds::contains)
            .toList();
    }

    private String migratedModelId(String currentModelId, String configuredModelId) {
        if (currentModelId == null) {
            return null;
        }
        List<EchoMindProperties.ModelMigration> migrations = config.getModelMigrations() == null
            ? List.of()
            : config.getModelMigrations();
        for (EchoMindProperties.ModelMigration migration : migrations) {
            if (matches(migration, currentModelId)) {
                if (configuredModelId != null && !configuredModelId.isBlank()) {
                    return configuredModelId;
                }
                String target = migration.getToModelId();
                return target == null || target.isBlank() ? null : target;
            }
        }
        return null;
    }

    private boolean matches(EchoMindProperties.ModelMigration migration, String modelId) {
        if (migration == null || modelId == null) {
            return false;
        }
        String exact = migration.getFrom();
        if (exact != null && !exact.isBlank() && modelId.equals(exact)) {
            return true;
        }
        String prefix = migration.getFromPrefix();
        return prefix != null && !prefix.isBlank() && modelId.startsWith(prefix);
    }
}
