package com.echomind.boot.autoconfigure;

import com.echomind.agent.AgentConfig;
import com.echomind.agent.AgentFactory;
import com.echomind.agent.pipeline.ExecutionPipeline;
import com.echomind.agent.store.AgentPersistenceService;
import com.echomind.boot.properties.EchoMindProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRuntimeBootstrapperTest {

    @Test
    void restoresPersistedAgentsAndMigratesOnlyStartupDefaults() {
        AgentConfig persisted = new AgentConfig();
        persisted.setAgentId("default");
        persisted.setName("Default");
        persisted.setSystemPrompt("可以使用 QQ 邮箱工具。");
        persisted.setModelId("anthropic:claude-3-sonnet");
        persisted.setSkillIds(List.of("weather-query", "qq-mail"));

        EchoMindProperties props = new EchoMindProperties();
        EchoMindProperties.AgentDef defaultAgent = new EchoMindProperties.AgentDef();
        defaultAgent.setAgentId("default");
        defaultAgent.setModelId("deepseek:deepseek-v4-flash");
        defaultAgent.setSkillIds(List.of("weather-query", "markdown-code", "date-query"));
        props.setAgents(List.of(defaultAgent));
        props.getRuntime().getAgentBootstrap().setDefaultSkillMergeIds(List.of("markdown-code", "date-query"));
        props.getRuntime().getAgentBootstrap().setModelMigrations(List.of(
            prefixMigration("anthropic:claude-", "deepseek:deepseek-v4-flash")
        ));
        props.getRuntime().getRetiredSkills().setSkillIds(List.of("qq-mail"));
        props.getRuntime().getRetiredSkills().setPromptReplacements(List.of(
            replacement("QQ 邮箱工具", "")
        ));

        AgentPersistenceService persistence = mock(AgentPersistenceService.class);
        when(persistence.loadAll()).thenReturn(List.of(persisted));
        when(persistence.exists("default")).thenReturn(true);

        AgentFactory factory = new AgentRuntimeBootstrapper(props)
            .restore(mock(ExecutionPipeline.class), props, persistence);

        assertThat(factory.allAgents()).containsKey("default");
        assertThat(persisted.getSkillIds()).containsExactly("weather-query", "markdown-code", "date-query");
        assertThat(persisted.getSystemPrompt()).doesNotContain("QQ 邮箱工具");
        assertThat(persisted.getModelId()).isEqualTo("deepseek:deepseek-v4-flash");
        verify(persistence).save(persisted);
    }

    @Test
    void migratesLegacyDeepSeekChatModelToV4FlashModel() {
        AgentConfig persisted = new AgentConfig();
        persisted.setAgentId("default");
        persisted.setName("Default");
        persisted.setModelId("deepseek:deepseek-chat");
        persisted.setSkillIds(List.of("weather-query"));

        EchoMindProperties props = new EchoMindProperties();
        EchoMindProperties.AgentDef defaultAgent = new EchoMindProperties.AgentDef();
        defaultAgent.setAgentId("default");
        defaultAgent.setModelId("deepseek:deepseek-v4-flash");
        defaultAgent.setSkillIds(List.of("weather-query"));
        props.setAgents(List.of(defaultAgent));
        props.getRuntime().getAgentBootstrap().setModelMigrations(List.of(
            exactMigration("deepseek:deepseek-chat", "deepseek:deepseek-v4-flash")
        ));

        AgentPersistenceService persistence = mock(AgentPersistenceService.class);
        when(persistence.loadAll()).thenReturn(List.of(persisted));
        when(persistence.exists("default")).thenReturn(true);

        new AgentRuntimeBootstrapper(props).restore(mock(ExecutionPipeline.class), props, persistence);

        assertThat(persisted.getModelId()).isEqualTo("deepseek:deepseek-v4-flash");
        verify(persistence).save(persisted);
    }

    @Test
    void usesConfiguredBootstrapRulesForSkillMergeAndModelMigration() {
        AgentConfig persisted = new AgentConfig();
        persisted.setAgentId("default");
        persisted.setName("Default");
        persisted.setModelId("legacy-provider:old-model");
        persisted.setSkillIds(List.of("weather-query"));

        EchoMindProperties props = new EchoMindProperties();
        EchoMindProperties.AgentDef defaultAgent = new EchoMindProperties.AgentDef();
        defaultAgent.setAgentId("default");
        defaultAgent.setModelId("custom:new-model");
        defaultAgent.setSkillIds(List.of("weather-query", "custom-default-skill", "markdown-code"));
        props.setAgents(List.of(defaultAgent));

        EchoMindProperties.ModelMigration migration = new EchoMindProperties.ModelMigration();
        migration.setFrom("legacy-provider:old-model");
        migration.setToModelId("unused-when-agent-has-model");
        props.getRuntime().getAgentBootstrap().setModelMigrations(List.of(migration));
        props.getRuntime().getAgentBootstrap().setDefaultSkillMergeIds(List.of("custom-default-skill"));

        AgentPersistenceService persistence = mock(AgentPersistenceService.class);
        when(persistence.loadAll()).thenReturn(List.of(persisted));
        when(persistence.exists("default")).thenReturn(true);

        new AgentRuntimeBootstrapper(props).restore(mock(ExecutionPipeline.class), props, persistence);

        assertThat(persisted.getModelId()).isEqualTo("custom:new-model");
        assertThat(persisted.getSkillIds()).containsExactly("weather-query", "custom-default-skill");
        verify(persistence).save(persisted);
    }

    @Test
    void createsConfiguredFallbackAgentWhenNoPersistedOrConfiguredAgentsExist() {
        EchoMindProperties props = new EchoMindProperties();
        EchoMindProperties.AgentDef fallback = new EchoMindProperties.AgentDef();
        fallback.setAgentId("fallback");
        fallback.setName("Fallback Agent");
        fallback.setSystemPrompt("fallback prompt");
        fallback.setModelId("mock:mock-model");
        fallback.setSkillIds(List.of("calculator"));
        props.getRuntime().getAgentBootstrap().setFallbackAgent(fallback);

        AgentPersistenceService persistence = mock(AgentPersistenceService.class);
        when(persistence.loadAll()).thenReturn(List.of());

        AgentFactory factory = new AgentRuntimeBootstrapper(props)
            .restore(mock(ExecutionPipeline.class), props, persistence);

        assertThat(factory.allAgents()).containsKey("fallback");
        verify(persistence).save(org.mockito.ArgumentMatchers.argThat(agent ->
            "fallback".equals(agent.getAgentId())
                && "mock:mock-model".equals(agent.getModelId())
                && agent.getSkillIds().equals(List.of("calculator"))));
    }

    private EchoMindProperties.ModelMigration exactMigration(String from, String to) {
        EchoMindProperties.ModelMigration migration = new EchoMindProperties.ModelMigration();
        migration.setFrom(from);
        migration.setToModelId(to);
        return migration;
    }

    private EchoMindProperties.ModelMigration prefixMigration(String fromPrefix, String to) {
        EchoMindProperties.ModelMigration migration = new EchoMindProperties.ModelMigration();
        migration.setFromPrefix(fromPrefix);
        migration.setToModelId(to);
        return migration;
    }

    private EchoMindProperties.TextReplacement replacement(String from, String to) {
        EchoMindProperties.TextReplacement replacement = new EchoMindProperties.TextReplacement();
        replacement.setFrom(from);
        replacement.setTo(to);
        return replacement;
    }
}
