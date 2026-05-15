package com.echomind.console.service;

import com.echomind.agent.tool.SkillCapabilityService;
import com.echomind.common.model.SkillState;
import com.echomind.console.dto.SkillView;
import com.echomind.skill.marketplace.MarketplaceService;
import com.echomind.skill.marketplace.SkillRepository;
import com.echomind.skill.registry.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;
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
    void listSkillsIncludesPersistedMarketplaceSkillsWhenRuntimeRegistryIsEmpty() {
        SkillRegistry registry = mock(SkillRegistry.class);
        MarketplaceService marketplace = mock(MarketplaceService.class);
        SkillRepository uploaded = new SkillRepository();
        uploaded.setName("invoice-audit");
        uploaded.setVersion("1.2.0");
        uploaded.setDescription("Audit uploaded invoices");
        uploaded.setAuthor("EchoMind");
        uploaded.setTagsJson("[\"finance\",\"audit\"]");
        uploaded.setState(SkillState.ENABLED);
        when(marketplace.listAll()).thenReturn(List.of(uploaded));
        when(registry.listAll()).thenReturn(List.of());
        SkillApplicationService service = new SkillApplicationService(
            registry,
            marketplace,
            mock(SkillCapabilityService.class)
        );

        List<SkillView> skills = service.listSkills();

        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).skillId()).isEqualTo("invoice-audit@1.2.0");
        assertThat(skills.get(0).state()).isEqualTo(SkillState.ENABLED);
        assertThat(skills.get(0).metadata().tags()).containsExactly("finance", "audit");
    }

    @Test
    void enableAndDisableDelegateToCapabilityService() {
        SkillCapabilityService capabilityService = mock(SkillCapabilityService.class);
        MarketplaceService marketplace = mock(MarketplaceService.class);
        SkillApplicationService service = new SkillApplicationService(
            mock(SkillRegistry.class),
            marketplace,
            capabilityService
        );

        Map<String, String> enabled = service.enable("calculator");
        Map<String, String> disabled = service.disable("calculator");

        verify(capabilityService).enable("calculator");
        verify(capabilityService).disable("calculator");
        verify(marketplace).updateState("calculator", SkillState.ENABLED);
        verify(marketplace).updateState("calculator", SkillState.DISABLED);
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
