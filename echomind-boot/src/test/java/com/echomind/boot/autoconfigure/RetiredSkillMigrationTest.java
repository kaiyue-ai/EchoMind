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

        EchoMindProperties.RetiredSkills retiredSkills = new EchoMindProperties.RetiredSkills();
        retiredSkills.setSkillIds(List.of("qq-mail"));
        retiredSkills.setPromptReplacements(List.of(
            replacement("QQ 邮箱工具", ""),
            replacement("QQ Mail", "")
        ));

        boolean changed = new RetiredSkillMigration(retiredSkills).applyTo(config);

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

    @Test
    void removesLegacyWebSearchSkillBindings() {
        AgentConfig config = new AgentConfig();
        config.setAgentId("agent-web");
        config.setSkillIds(List.of("calculator", "web-search", "web-search@1.1.0", "date-query"));
        config.setSystemPrompt("可以使用 SearXNG 和 skill-websearch。");

        EchoMindProperties.RetiredSkills retiredSkills = new EchoMindProperties.RetiredSkills();
        retiredSkills.setSkillIds(List.of("web-search"));
        retiredSkills.setPromptReplacements(List.of(
            replacement("SearXNG", "open-websearch MCP"),
            replacement("skill-websearch", "open-websearch MCP")
        ));

        boolean changed = new RetiredSkillMigration(retiredSkills).applyTo(config);

        assertThat(changed).isTrue();
        assertThat(config.getSkillIds()).containsExactly("calculator", "date-query");
        assertThat(config.getSystemPrompt()).isEqualTo("可以使用 open-websearch MCP 和 open-websearch MCP。");
    }

    private EchoMindProperties.TextReplacement replacement(String from, String to) {
        EchoMindProperties.TextReplacement replacement = new EchoMindProperties.TextReplacement();
        replacement.setFrom(from);
        replacement.setTo(to);
        return replacement;
    }
}
