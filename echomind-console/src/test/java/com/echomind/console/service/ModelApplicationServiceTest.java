package com.echomind.console.service;

import com.echomind.console.dto.ModelSwitchRequest;
import com.echomind.llm.router.DynamicModelRouter;
import com.echomind.llm.router.ModelCapability;
import com.echomind.llm.router.ModelProviderRegistry;
import com.echomind.llm.router.ModelSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 模型应用服务测试。
 *
 * <p>确认模型列表只从路由器读取，默认模型切换只通过Registry完成，
 * Controller无需知道模型注册表内部结构。</p>
 */
class ModelApplicationServiceTest {

    @Test
    void listModelsDelegatesToRouter() {
        DynamicModelRouter router = mock(DynamicModelRouter.class);
        ModelApplicationService service = new ModelApplicationService(
            router,
            mock(ModelProviderRegistry.class)
        );
        ModelSpec model = new ModelSpec("mock", "mock-model", Set.of(ModelCapability.TEXT), true);
        when(router.listAll()).thenReturn(List.of(model));

        assertThat(service.listModels()).containsExactly(model);
    }

    @Test
    void switchModelDelegatesToRegistry() {
        ModelProviderRegistry registry = mock(ModelProviderRegistry.class);
        ModelApplicationService service = new ModelApplicationService(
            mock(DynamicModelRouter.class),
            registry
        );

        Map<String, String> result = service.switchModel(new ModelSwitchRequest("mock", "mock-model"));

        verify(registry).setDefault("mock", "mock-model");
        assertThat(result).containsEntry("status", "switched");
        assertThat(result).containsEntry("providerId", "mock");
        assertThat(result).containsEntry("modelName", "mock-model");
    }

    @Test
    void invalidSwitchRequestIsRejected() {
        ModelProviderRegistry registry = mock(ModelProviderRegistry.class);
        ModelApplicationService service = new ModelApplicationService(
            mock(DynamicModelRouter.class),
            registry
        );

        assertThatThrownBy(() -> service.switchModel(new ModelSwitchRequest("", "mock-model")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("providerId不能为空");

        verifyNoInteractions(registry);
    }
}
