package com.echomind.console.service;

import com.echomind.agent.store.AgentPersistenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 解析模型调用前可提前确定的 Provider。
 *
 * <p>公开聊天优先使用请求模型；Team 内部调用没有每次请求模型时，回退到 Agent 默认模型。</p>
 */
@Service
@RequiredArgsConstructor
public class ChatProviderResolver {

    private final AgentPersistenceService agentPersistenceService;

    public Optional<String> resolveProviderId(String agentId, String requestedModelId) {
        Optional<String> requestedProvider = providerFromModelId(requestedModelId);
        if (requestedProvider.isPresent()) {
            return requestedProvider;
        }
        return agentPersistenceService.find(agentId)
            .flatMap(config -> providerFromModelId(config.getModelId()));
    }

    private Optional<String> providerFromModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return Optional.empty();
        }
        int separator = modelId.indexOf(':');
        if (separator <= 0) {
            return Optional.empty();
        }
        String providerId = modelId.substring(0, separator).trim();
        return providerId.isBlank() ? Optional.empty() : Optional.of(providerId);
    }
}
