package com.echomind.boot.autoconfigure;

import com.echomind.agent.AgentConfig;
import com.echomind.boot.properties.EchoMindProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RetiredSkillMigrationTest {

    @Test
    void removesRetiredSkillIdsAndPromptMentions() {
        AgentConfig config = new AgentConfig();
        config.setAgentId("agent-a");
        config.setSkillIds(List.of("calculator", "qq-mail", "qq-mail@old"));
        config.setSystemPrompt("可以使用天气、QQ 邮箱工具和 QQ Mail。");

        boolean changed = new RetiredSkillMigration(new EchoMindProperties.RetiredSkills()).applyTo(config);

        assertThat(changed).isTrue();
        assertThat(config.getSkillIds()).containsExactly("calculator");
        assertThat(config.getSystemPrompt()).doesNotContain("QQ 邮箱工具", "QQ Mail");
    }

    @Test
    void usesConfiguredRetiredSkillIdsAndPromptReplacements() {
        AgentConfig config = new AgentConfig();
        config.setAgentId("agent-a");
        config.setSkillIds(List.of("calculator", "legacy-skill", "legacy-skill@1.0.0"));
        config.setSystemPrompt("可以使用 Legacy Tool。");

        EchoMindProperties.RetiredSkills retiredSkills = new EchoMindProperties.RetiredSkills();
        retiredSkills.setSkillIds(List.of("legacy-skill"));
        EchoMindProperties.TextReplacement replacement = new EchoMindProperties.TextReplacement();
        replacement.setFrom("Legacy Tool");
        replacement.setTo("新工具");
        retiredSkills.setPromptReplacements(List.of(replacement));

        RetiredSkillMigration migration = new RetiredSkillMigration(retiredSkills);
        boolean changed = migration.applyTo(config);

        assertThat(changed).isTrue();
        assertThat(migration.retiredSkillIds()).containsExactly("legacy-skill");
        assertThat(config.getSkillIds()).containsExactly("calculator");
        assertThat(config.getSystemPrompt()).isEqualTo("可以使用 新工具。");
    }
}
