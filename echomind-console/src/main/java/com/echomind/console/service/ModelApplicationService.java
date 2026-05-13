package com.echomind.console.service;

import com.echomind.console.dto.ModelSwitchRequest;
import com.echomind.llm.router.DynamicModelRouter;
import com.echomind.llm.router.ModelProviderRegistry;
import com.echomind.llm.router.ModelSpec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 模型应用服务。
 *
 * <p>模型列表来自运行时ModelProviderRegistry，默认模型切换也只作用于运行时路由。
 * 后续如果要把默认模型持久化，可以在这里接入配置表，而不需要改Controller。</p>
 */
@Service
@RequiredArgsConstructor
public class ModelApplicationService {

    private final DynamicModelRouter router;
    private final ModelProviderRegistry registry;

    public List<ModelSpec> listModels() {
        return router.listAll();
    }

    public Map<String, String> switchModel(ModelSwitchRequest request) {
        if (request == null || request.providerId() == null || request.providerId().isBlank()) {
            throw new IllegalArgumentException("providerId不能为空");
        }
        if (request.modelName() == null || request.modelName().isBlank()) {
            throw new IllegalArgumentException("modelName不能为空");
        }
        registry.setDefault(request.providerId(), request.modelName());
        return Map.of(
            "status", "switched",
            "providerId", request.providerId(),
            "modelName", request.modelName()
        );
    }
}
