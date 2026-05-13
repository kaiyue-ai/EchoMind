package com.echomind.console.service;

import com.echomind.agent.tool.SkillCapabilityService;
import com.echomind.console.dto.SkillRepositoryView;
import com.echomind.console.dto.SkillView;
import com.echomind.skill.marketplace.MarketplaceService;
import com.echomind.skill.marketplace.SkillRepository;
import com.echomind.skill.registry.SkillRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Skill应用服务。
 *
 * <p>Skill启停会同时影响运行时SkillRegistry和统一CapabilityRegistry。
 * Controller不直接操作这些运行时对象，所有生命周期操作都在这里收口。</p>
 */
@Service
@RequiredArgsConstructor
public class SkillApplicationService {

    private final SkillRegistry registry;
    private final MarketplaceService marketplace;
    private final SkillCapabilityService capabilityService;

    /** 列出当前运行时已加载的Skill。 */
    public List<SkillView> listSkills() {
        return registry.listAll().stream()
            .map(SkillView::from)
            .toList();
    }

    /** 启用Skill并同步暴露为Agent/MCP可用能力。 */
    public Map<String, String> enable(String skillId) {
        capabilityService.enable(skillId);
        return Map.of("status", "enabled", "skillId", skillId);
    }

    /** 禁用Skill并从Agent/MCP能力视图中移除。 */
    public Map<String, String> disable(String skillId) {
        capabilityService.disable(skillId);
        return Map.of("status", "disabled", "skillId", skillId);
    }

    /** 上传Skill JAR，加载成功后立即启用并同步能力注册表。 */
    public SkillRepositoryView upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Skill JAR不能为空");
        }
        try {
            Path tempFile = Files.createTempFile("skill-", ".jar");
            file.transferTo(tempFile.toFile());
            SkillRepository entity = marketplace.upload(tempFile);
            capabilityService.enable(entity.getName() + "@" + entity.getVersion());
            return SkillRepositoryView.from(entity);
        } catch (Exception e) {
            throw new IllegalArgumentException("Skill上传失败: " + e.getMessage(), e);
        }
    }

    /** 删除Skill，并同步收回对Agent和MCP暴露的能力。 */
    public void delete(String identifier) {
        marketplace.delete(identifier)
            .ifPresent(capabilityService::unregisterExposure);
    }
}
