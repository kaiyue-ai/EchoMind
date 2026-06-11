package com.echomind.boot.autoconfigure;

import com.echomind.agent.AgentConfig;
import com.echomind.agent.AgentFactory;
import com.echomind.agent.pipeline.ExecutionPipeline;
import com.echomind.agent.store.AgentPersistenceService;
import com.echomind.boot.properties.EchoMindProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ResourceLoader;

import java.util.List;

/**
 * Restores persisted Agent configuration into the runtime index at startup.
 */
@Slf4j
final class AgentRuntimeBootstrapper {

    private final AgentBootstrapPolicy agentBootstrapPolicy;
    private final RetiredSkillMigration retiredSkillMigration;

    AgentRuntimeBootstrapper(EchoMindProperties props) {
        this(props, null);
    }

    AgentRuntimeBootstrapper(EchoMindProperties props, ResourceLoader resourceLoader) {
        EchoMindProperties.Runtime runtime = props == null ? new EchoMindProperties.Runtime() : props.getRuntime();
        if (runtime == null) {
            runtime = new EchoMindProperties.Runtime();
        }
        this.agentBootstrapPolicy = new AgentBootstrapPolicy(runtime.getAgentBootstrap(), resourceLoader);
        this.retiredSkillMigration = new RetiredSkillMigration(runtime.getRetiredSkills());
    }

    AgentFactory restore(ExecutionPipeline pipeline, EchoMindProperties props,
                         AgentPersistenceService persistenceService) {
        AgentFactory factory = new AgentFactory(pipeline);
        for (AgentConfig persisted : persistenceService.loadAll()) {
            if (migratePersistedAgent(persisted, props)) {
                persistenceService.save(persisted);
            }
            factory.create(persisted);
        }
        for (var def : configuredAgents(props)) {
            if (persistenceService.exists(def.getAgentId())) {
                continue;
            }
            AgentConfig config = agentBootstrapPolicy.fromConfiguredAgent(def);
            persistenceService.save(config);
            factory.create(config);
        }
        if (factory.allAgents().isEmpty()) {
            AgentConfig config = agentBootstrapPolicy.fallbackAgent();
            persistenceService.save(config);
            factory.create(config);
        }
        return factory;
    }

    private boolean migratePersistedAgent(AgentConfig persisted, EchoMindProperties props) {
        boolean changed = retiredSkillMigration.applyTo(persisted);
        for (var def : configuredAgents(props)) {
            if (agentBootstrapPolicy.mergeConfiguredDefaults(persisted, def)) {
                log.info("Applied configured startup defaults to persisted agent {}", persisted.getAgentId());
                changed = true;
                break;
            }
        }
        return changed;
    }

    private List<EchoMindProperties.AgentDef> configuredAgents(EchoMindProperties props) {
        return props == null || props.getAgents() == null ? List.of() : props.getAgents();
    }
}
