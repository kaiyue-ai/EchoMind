package com.echomind.agent.store;

import com.echomind.agent.AgentConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Agent持久化服务单元测试。
 *
 * <p>这些测试不依赖真实MySQL，而是用Mock Repository验证转换逻辑。
 * 真正的数据库建表与重启恢复由部署验收覆盖；这里重点锁住“Agent配置必须
 * 被转换成可持久化实体，并且Skill列表不能丢失”。</p>
 */
class AgentPersistenceServiceTest {

    @Test
    void saveSerializesSkillIdsIntoEntity() {
        AgentRepository repository = mock(AgentRepository.class);
        when(repository.findById("agent-a")).thenReturn(Optional.empty());
        AgentPersistenceService service = new AgentPersistenceService(repository);

        AgentConfig config = new AgentConfig();
        config.setAgentId("agent-a");
        config.setName("测试Agent");
        config.setSystemPrompt("你是一个测试Agent");
        config.setModelId("mock:mock-model");
        config.setSkillIds(List.of("calculator", "web-search"));

        service.save(config);

        ArgumentCaptor<AgentEntity> captor = ArgumentCaptor.forClass(AgentEntity.class);
        verify(repository).save(captor.capture());
        AgentEntity saved = captor.getValue();

        assertThat(saved.getAgentId()).isEqualTo("agent-a");
        assertThat(saved.getName()).isEqualTo("测试Agent");
        assertThat(saved.getSystemPrompt()).isEqualTo("你是一个测试Agent");
        assertThat(saved.getModelId()).isEqualTo("mock:mock-model");
        assertThat(saved.getSkillIdsJson()).isEqualTo("[\"calculator\",\"web-search\"]");
    }

    @Test
    void loadAllRestoresConfigFromEntity() {
        AgentRepository repository = mock(AgentRepository.class);
        AgentEntity entity = new AgentEntity();
        entity.setAgentId("agent-b");
        entity.setName("恢复Agent");
        entity.setSystemPrompt("从数据库恢复");
        entity.setModelId("deepseek:deepseek-v4-flash");
        entity.setSkillIdsJson("[\"weather-query\",\"date-query\"]");
        when(repository.findAll()).thenReturn(List.of(entity));

        AgentPersistenceService service = new AgentPersistenceService(repository);

        List<AgentConfig> configs = service.loadAll();

        assertThat(configs).hasSize(1);
        AgentConfig restored = configs.get(0);
        assertThat(restored.getAgentId()).isEqualTo("agent-b");
        assertThat(restored.getName()).isEqualTo("恢复Agent");
        assertThat(restored.getSystemPrompt()).isEqualTo("从数据库恢复");
        assertThat(restored.getModelId()).isEqualTo("deepseek:deepseek-v4-flash");
        assertThat(restored.getSkillIds()).containsExactly("weather-query", "date-query");
    }
}
