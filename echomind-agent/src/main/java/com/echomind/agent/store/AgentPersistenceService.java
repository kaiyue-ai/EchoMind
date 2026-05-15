package com.echomind.agent.store;

import com.echomind.agent.AgentConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Agent持久化服务。
 *
 * <p>负责在运行时Agent配置和MySQL实体之间转换，避免Controller直接操作JPA实体。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class AgentPersistenceService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final AgentRepository repository;

    /**
     * 保存Agent配置。
     *
     * @param config Agent配置
     * @return 保存后的配置
     */
    public AgentConfig save(AgentConfig config) {
        AgentEntity entity = repository.findById(config.getAgentId()).orElseGet(AgentEntity::new);
        entity.setAgentId(config.getAgentId());
        entity.setName(config.getName());
        entity.setSystemPrompt(config.getSystemPrompt());
        entity.setModelId(config.getModelId());
        entity.setSkillIdsJson(toJson(config.getSkillIds()));
        repository.save(entity);
        return config;
    }

    /**
     * 加载全部已持久化Agent配置。
     *
     * @return Agent配置列表
     */
    public List<AgentConfig> loadAll() {
        return repository.findAll().stream()
            .map(this::toConfig)
            .toList();
    }

    /**
     * 判断指定Agent是否已持久化。
     *
     * @param agentId Agent标识
     * @return true表示数据库中存在
     */
    public boolean exists(String agentId) {
        return repository.existsById(agentId);
    }

    /**
     * 从数据库删除指定Agent。
     *
     * @param agentId Agent标识
     * @return true表示删除成功，false表示Agent不存在
     */
    public boolean delete(String agentId) {
        if (!repository.existsById(agentId)) {
            return false;
        }
        repository.deleteById(agentId);
        return true;
    }

    private AgentConfig toConfig(AgentEntity entity) {
        AgentConfig config = new AgentConfig();
        config.setAgentId(entity.getAgentId());
        config.setName(entity.getName());
        config.setSystemPrompt(entity.getSystemPrompt());
        config.setModelId(entity.getModelId());
        config.setSkillIds(fromJson(entity.getSkillIdsJson()));
        return config;
    }

    private String toJson(List<String> skillIds) {
        try {
            return MAPPER.writeValueAsString(skillIds == null ? List.of() : skillIds);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize agent skill ids: {}", e.getMessage());
            return "[]";
        }
    }

    private List<String> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, STRING_LIST);
        } catch (Exception e) {
            log.warn("Failed to parse agent skill ids: {}", e.getMessage());
            return List.of();
        }
    }
}
