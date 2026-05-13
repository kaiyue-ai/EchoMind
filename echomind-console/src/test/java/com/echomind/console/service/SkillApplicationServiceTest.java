package com.echomind.console.service;

import com.echomind.agent.tool.SkillCapabilityService;
import com.echomind.skill.marketplace.MarketplaceService;
import com.echomind.skill.registry.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Skill应用服务测试。
 *
 * <p>Skill启停会影响运行时注册表和Agent/MCP统一能力视图，测试用例聚焦这些副作用
 * 是否被收敛在应用服务层。</p>
 */
class SkillApplicationServiceTest {

    @Test
    void enableAndDisableDelegateToCapabilityService() {
        SkillCapabilityService capabilityService = mock(SkillCapabilityService.class);
        SkillApplicationService service = new SkillApplicationService(
            mock(SkillRegistry.class),
            mock(MarketplaceService.class),
            capabilityService
        );

        Map<String, String> enabled = service.enable("calculator");
        Map<String, String> disabled = service.disable("calculator");

        verify(capabilityService).enable("calculator");
        verify(capabilityService).disable("calculator");
        assertThat(enabled).containsEntry("status", "enabled");
        assertThat(disabled).containsEntry("status", "disabled");
    }

    @Test
    void deleteUnregistersExposureBeforeMarketplaceDelete() {
        SkillCapabilityService capabilityService = mock(SkillCapabilityService.class);
        MarketplaceService marketplace = mock(MarketplaceService.class);
        when(marketplace.delete("db-id")).thenReturn(Optional.of("calculator@1.0.0"));
        SkillApplicationService service = new SkillApplicationService(
            mock(SkillRegistry.class),
            marketplace,
            capabilityService
        );

        service.delete("db-id");

        verify(marketplace).delete("db-id");
        verify(capabilityService).unregisterExposure("calculator@1.0.0");
    }

    @Test
    void emptyUploadIsRejectedBeforeSideEffects() {
        SkillCapabilityService capabilityService = mock(SkillCapabilityService.class);
        MarketplaceService marketplace = mock(MarketplaceService.class);
        SkillApplicationService service = new SkillApplicationService(
            mock(SkillRegistry.class),
            marketplace,
            capabilityService
        );

        assertThatThrownBy(() -> service.upload(new MockMultipartFile("file", new byte[0])))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Skill JAR不能为空");

        verifyNoInteractions(marketplace, capabilityService);
    }
}
