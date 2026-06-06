package com.echomind.console.service;

import com.echomind.agent.Agent;
import com.echomind.agent.AgentConfig;
import com.echomind.agent.AgentFactory;
import com.echomind.agent.pipeline.ExecutionPipeline;
import com.echomind.agent.store.AgentPersistenceService;
import com.echomind.console.dto.AgentView;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Agent应用服务单元测试。
 *
 * <p>这里验证REST管理面之外的业务顺序：创建Agent时必须先写入持久化事实来源，
 * 再刷新运行时AgentFactory。这个顺序能避免“接口看似创建成功，但重启后丢失”的问题。</p>
 */
class AgentApplicationServiceTest {

    @Test
    void createOrUpdatePersistsBeforeRuntimeRegistration() {
        AgentFactory factory = mock(AgentFactory.class);
        AgentPersistenceService persistenceService = mock(AgentPersistenceService.class);
        AgentApplicationService service = new AgentApplicationService(
            factory, persistenceService, mock(AgentKnowledgeApplicationService.class));

        AgentConfig config = validConfig();
        Agent runtimeAgent = new Agent(
            config.getAgentId(),
            config.getName(),
            config.getSystemPrompt(),
            config.getModelId(),
            config.getSkillIds(),
            new ExecutionPipeline(List.of())
        );
        when(factory.create(config)).thenReturn(runtimeAgent);

        AgentView view = service.createOrUpdate(config);

        InOrder order = inOrder(persistenceService, factory);
        order.verify(persistenceService).save(same(config));
        order.verify(factory).create(same(config));
        assertThat(view.agentId()).isEqualTo("agent-test");
        assertThat(view.name()).isEqualTo("测试Agent");
        assertThat(view.skillIds()).containsExactly("calculator");
    }

    @Test
    void createOrUpdateNormalizesNullSkillIds() {
        AgentFactory factory = mock(AgentFactory.class);
        AgentApplicationService service = new AgentApplicationService(
            factory,
            mock(AgentPersistenceService.class),
            mock(AgentKnowledgeApplicationService.class)
        );
        AgentConfig config = validConfig();
        config.setSkillIds(null);
        when(factory.create(config)).thenReturn(new Agent(
            config.getAgentId(),
            config.getName(),
            config.getSystemPrompt(),
            config.getModelId(),
            List.of(),
            new ExecutionPipeline(List.of())
        ));

        service.createOrUpdate(config);

        assertThat(config.getSkillIds()).isEmpty();
    }

    @Test
    void createOrUpdateRejectsBlankAgentIdBeforePersistence() {
        AgentFactory factory = mock(AgentFactory.class);
        AgentPersistenceService persistenceService = mock(AgentPersistenceService.class);
        AgentApplicationService service = new AgentApplicationService(
            factory,
            persistenceService,
            mock(AgentKnowledgeApplicationService.class)
        );
        AgentConfig config = validConfig();
        config.setAgentId(" ");

        assertThatThrownBy(() -> service.createOrUpdate(config))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("agentId不能为空");

        verifyNoInteractions(persistenceService, factory);
    }

    @Test
    void deleteAgentCleansKnowledgeBeforeRemovingRuntimeAndPersistence() {
        AgentFactory factory = mock(AgentFactory.class);
        AgentPersistenceService persistenceService = mock(AgentPersistenceService.class);
        AgentKnowledgeApplicationService knowledgeService = mock(AgentKnowledgeApplicationService.class);
        AgentApplicationService service = new AgentApplicationService(
            factory,
            persistenceService,
            knowledgeService
        );
        when(persistenceService.exists("agent-test")).thenReturn(true);

        service.deleteAgent("agent-test");

        InOrder order = inOrder(persistenceService, knowledgeService, factory);
        order.verify(persistenceService).exists("agent-test");
        order.verify(knowledgeService).deleteAll("agent-test");
        order.verify(factory).remove("agent-test");
        order.verify(persistenceService).delete("agent-test");
    }

    private AgentConfig validConfig() {
        AgentConfig config = new AgentConfig();
        config.setAgentId("agent-test");
        config.setName("测试Agent");
        config.setSystemPrompt("你是测试Agent");
        config.setModelId("mock:mock-model");
        config.setSkillIds(List.of("calculator"));
        return config;
    }
}
