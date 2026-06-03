package com.echomind.console.service;

import com.echomind.agent.tool.skill.SkillCapabilityService;
import com.echomind.common.model.SkillState;
import com.echomind.console.dto.SkillEntityView;
import com.echomind.console.dto.SkillView;
import com.echomind.skill.marketplace.MarketplaceService;
import com.echomind.skill.marketplace.SkillEntity;
import com.echomind.skill.registry.SkillRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill应用服务。
 *
 * <p>Skill启停会同时影响运行时SkillRegistry和统一CapabilityRegistry。
 * Controller不直接操作这些运行时对象，所有生命周期操作都在这里收口。</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SkillApplicationService {

    private final SkillRegistry registry;
    private final MarketplaceService marketplace;
    private final SkillCapabilityService capabilityService;

    /**
     * 列出所有Skill（运行时 + 持久化市场）。
     *
     * <p>Skill可能存在于三个地方：</p>
     * <ol>
     *   <li>运行时注册中心（从 auto-load-path 或 marketplace 加载）</li>
     *   <li>marketplace 数据库（上传后持久化）</li>
     * </ol>
     *
     * <p>本方法以 skillId 为键合并两者：数据库是上传 Skill 的事实来源，
     * 运行时中但未持久化的内置 Skill 也会继续展示。</p>
     */
    public List<SkillView> listSkills() {
        Map<String, SkillView> merged = new LinkedHashMap<>();

        for (SkillEntity entity : marketplace.listAll()) {
            String skillId = entity.getName() + "@" + entity.getVersion();
            merged.put(skillId, SkillView.fromMarketplace(entity));
        }

        registry.listAll().forEach(reg -> {
            String skillId = reg.getSkillId();
            merged.put(skillId, SkillView.from(reg));
        });

        return new ArrayList<>(merged.values());
    }

    /** 启用Skill并同步暴露为Agent/MCP可用能力。 */
    public Map<String, String> enable(String skillId) {
        capabilityService.enable(skillId);
        marketplace.updateState(skillId, SkillState.ENABLED);
        return Map.of("status", "enabled", "skillId", skillId);
    }

    /** 禁用Skill并从Agent/MCP能力视图中移除。 */
    public Map<String, String> disable(String skillId) {
        capabilityService.disable(skillId);
        marketplace.updateState(skillId, SkillState.DISABLED);
        return Map.of("status", "disabled", "skillId", skillId);
    }

    /** 上传Skill JAR，加载成功后立即启用并同步能力注册表。 */
    /**
     * 上传 Skill JAR 包并启用
     *
     * <p>执行流程：
     * <ol>
     *   <li>校验上传文件非空</li>
     *   <li>将 JAR 包保存到临时文件</li>
     *   <li>调用 Marketplace 上传到技能市场</li>
     *   <li>在 CapabilityRegistry 中启用该技能</li>
     *   <li>更新技能状态为 ENABLED</li>
     *   <li>返回技能实体视图</li>
     * </ol>
     * </p>
     *
     * @param file Skill JAR 文件
     * @return 技能实体视图
     * @throws IllegalArgumentException 文件为空或上传失败时抛出
     */
    public SkillEntityView upload(MultipartFile file) {
        // ============================================
        // 阶段 1：校验上传文件
        // ============================================
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Skill JAR不能为空");
        }

        Path tempFile = null;
        try {
            // ============================================
            // 阶段 2：创建临时文件（在 finally 中清理）
            // ============================================
            tempFile = Files.createTempFile("skill-", ".jar");

            // ============================================
            // 阶段 3：保存 JAR 到临时文件
            // ============================================
            file.transferTo(tempFile.toFile());

            // ============================================
            // 阶段 4：上传到技能市场
            // ============================================
            SkillEntity entity = marketplace.upload(tempFile);

            // ============================================
            // 阶段 5：在 CapabilityRegistry 中启用技能
            // ============================================
            capabilityService.enable(entity.getName() + "@" + entity.getVersion());

            // ============================================
            // 阶段 6：更新技能状态为 ENABLED
            // ============================================
            entity = marketplace.updateState(entity.getName() + "@" + entity.getVersion(), SkillState.ENABLED)
                .orElse(entity);

            // ============================================
            // 阶段 7：返回技能实体视图
            // ============================================
            return SkillEntityView.from(entity);
        } catch (Exception e) {
            // 异常处理：包装为 IllegalArgumentException 并抛出
            throw new IllegalArgumentException("Skill 上传失败：" + e.getMessage(), e);
        } finally {
            // ============================================
            // 阶段 8：清理临时文件（无论成功失败都删除）
            // ============================================
            try {
                if (tempFile != null) {
                    Files.deleteIfExists(tempFile);
                }
            } catch (IOException e) {
                log.warn("Failed to delete temp file: {}", tempFile, e);
            }
        }
    }

    /** 删除Skill，并同步收回对Agent和MCP暴露的能力。 */
    public void delete(String identifier) {
        marketplace.delete(identifier)
            .ifPresent(capabilityService::unregisterExposure);
    }
}
