package com.echomind.console.service;

import com.echomind.agent.tool.skill.SkillCapabilityService;
import com.echomind.common.model.SkillState;
import com.echomind.console.dto.SkillView;
import com.echomind.skill.api.Skill;
import com.echomind.skill.api.SkillMetadata;
import com.echomind.skill.api.SkillRequest;
import com.echomind.skill.api.SkillResult;
import com.echomind.skill.marketplace.MarketplaceService;
import com.echomind.skill.marketplace.SkillEntity;
import com.echomind.skill.registry.SkillRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.concurrent.CompletableFuture;
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
        SkillEntity uploaded = new SkillEntity();
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
    void listSkillsPrefersRuntimeMetadataOverStaleMarketplaceMetadata() {
        SkillRegistry registry = new SkillRegistry();
        MarketplaceService marketplace = mock(MarketplaceService.class);
        SkillEntity stale = new SkillEntity();
        stale.setName("report-writer");
        stale.setVersion("1.0.0");
        stale.setDescription("旧报告描述");
        stale.setParameterSchemaJson("{\"properties\":{\"action\":{\"enum\":[\"list\"]}}}");
        stale.setState(SkillState.ENABLED);
        when(marketplace.listAll()).thenReturn(List.of(stale));
        registry.register(new FakeSkill(new SkillMetadata(
            "report-writer",
            "1.0.0",
            "报告生成工具：支持 draft/review/export",
            Map.of("properties", Map.of(
                "action", Map.of("enum", List.of("draft", "review", "export")),
                "topic", Map.of("type", "string")
            )),
            List.of(),
            "EchoMind",
            List.of("报告")
        )), getClass().getClassLoader());
        SkillApplicationService service = new SkillApplicationService(
            registry,
            marketplace,
            mock(SkillCapabilityService.class)
        );

        List<SkillView> skills = service.listSkills();

        assertThat(skills).hasSize(1);
        assertThat(skills.get(0).metadata().description()).contains("draft/review/export");
        assertThat(skills.get(0).metadata().parameterSchema().toString()).contains("draft", "export", "topic");
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

    private record FakeSkill(SkillMetadata metadata) implements Skill {
        @Override
        public CompletableFuture<SkillResult> execute(SkillRequest request) {
            return CompletableFuture.completedFuture(SkillResult.success("", 0));
        }
    }
}
