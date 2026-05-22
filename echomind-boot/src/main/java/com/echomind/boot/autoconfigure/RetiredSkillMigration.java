package com.echomind.boot.autoconfigure;

import com.echomind.agent.AgentConfig;
import com.echomind.boot.properties.EchoMindProperties;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;

/**
 * Startup cleanup for skills that have been removed from the platform.
 */
@Slf4j
final class RetiredSkillMigration {

    private final EchoMindProperties.RetiredSkills config;

    RetiredSkillMigration(EchoMindProperties.RetiredSkills config) {
        this.config = config == null ? new EchoMindProperties.RetiredSkills() : config;
    }

    Set<String> retiredSkillIds() {
        List<String> skillIds = config.getSkillIds();
        return skillIds == null ? Set.of() : Set.copyOf(skillIds);
    }

    boolean applyTo(AgentConfig config) {
        boolean skillsChanged = removeRetiredSkills(config);
        boolean promptChanged = removeRetiredPromptMentions(config);
        return skillsChanged || promptChanged;
    }

    private boolean removeRetiredSkills(AgentConfig config) {
        if (config == null || config.getSkillIds() == null || config.getSkillIds().isEmpty()) {
            return false;
        }
        List<String> filtered = config.getSkillIds().stream()
            .filter(skillId -> !isRetiredSkill(skillId))
            .toList();
        if (filtered.size() == config.getSkillIds().size()) {
            return false;
        }
        config.setSkillIds(filtered);
        log.info("Removed retired skills from persisted agent {}", config.getAgentId());
        return true;
    }

    private boolean isRetiredSkill(String skillId) {
        if (skillId == null) {
            return false;
        }
        return retiredSkillIds().stream()
            .anyMatch(retired -> retired.equals(skillId) || skillId.startsWith(retired + "@"));
    }

    private boolean removeRetiredPromptMentions(AgentConfig config) {
        if (config == null || config.getSystemPrompt() == null || config.getSystemPrompt().isBlank()) {
            return false;
        }
        String before = config.getSystemPrompt();
        String after = before;
        List<EchoMindProperties.TextReplacement> replacements = this.config.getPromptReplacements();
        if (replacements != null) {
            for (EchoMindProperties.TextReplacement replacement : replacements) {
                if (replacement == null || replacement.getFrom() == null || replacement.getFrom().isBlank()) {
                    continue;
                }
                after = after.replace(replacement.getFrom(), replacement.getTo() == null ? "" : replacement.getTo());
            }
        }
        after = after.replaceAll(" {2,}", " ").trim();
        if (before.equals(after)) {
            return false;
        }
        config.setSystemPrompt(after);
        log.info("Removed retired skill prompt mentions from persisted agent {}", config.getAgentId());
        return true;
    }
}
