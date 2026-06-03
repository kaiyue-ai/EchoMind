package com.echomind.console.controller.rest;

import com.echomind.console.dto.SkillEntityView;
import com.echomind.console.dto.SkillView;
import com.echomind.console.service.SkillApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 技能管理控制器 —— 提供技能的全生命周期 REST API。
 *
 * <p>技能（Skill）是 EchoMind 平台的核心扩展机制。本控制器管理以下操作：
 * <ol>
 *   <li><b>查看</b>：列出所有已注册的技能及其状态</li>
 *   <li><b>启用/禁用</b>：运行时切换技能的可用状态，无需重启</li>
 *   <li><b>上传</b>：从技能市场（Marketplace）上传新的技能 JAR</li>
 *   <li><b>删除</b>：删除已注册的技能实例</li>
 * </ol>
 *
 * <p>设计要点：
 * <ul>
 *   <li>启用/禁用操作直接作用于 {@link SkillRegistry}，效果即时生效。</li>
 *   <li>上传操作先将 MultipartFile 写入临时文件，再委托
 *       {@link MarketplaceService} 加载并注册。</li>
 *   <li>删除操作从注册中心移除技能并从持久化存储中清除。</li>
 *   <li>启用、禁用、上传和删除会同步更新统一能力注册中心。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/skills")
@RequiredArgsConstructor
public class SkillController {

    /** Skill应用服务，收口Skill生命周期与能力注册同步。 */
    private final SkillApplicationService skillService;

    /**
     * 列出所有已注册的技能。
     *
     * <p>返回包含元数据（名称、版本、描述）、当前状态（启用/禁用）等信息。
     *
     * @return 技能注册信息列表
     */
    @GetMapping
    public ResponseEntity<List<SkillView>> listSkills() {
        return ResponseEntity.ok(skillService.listSkills());
    }

    /**
     * 启用指定技能。
     *
     * <p>启用后，该技能即可被 Agent 执行管线调用。
     *
     * @param skillId 技能唯一标识
     * @return 包含操作状态和技能 ID 的响应
     */
    @PostMapping("/{skillId}/enable")
    public ResponseEntity<Map<String, String>> enable(@PathVariable String skillId) {
        return ResponseEntity.ok(skillService.enable(skillId));
    }

    /**
     * 禁用指定技能。
     *
     * <p>禁用后，该技能不会被执行管线调用，但依然保留在注册中心。
     *
     * @param skillId 技能唯一标识
     * @return 包含操作状态和技能 ID 的响应
     */
    @PostMapping("/{skillId}/disable")
    public ResponseEntity<Map<String, String>> disable(@PathVariable String skillId) {
        return ResponseEntity.ok(skillService.disable(skillId));
    }

    /**
     * 上传新的技能 JAR。
     *
     * <p>接收 MultipartFile 形式的 JAR 文件，先写入临时路径，
     * 再交给 {@link MarketplaceService} 完成加载、验证和注册。
     *
     * @param file 上传的技能 JAR 文件
     * @return 持久化后的技能仓库实体；若处理失败则返回 500
     */
    @PostMapping("/upload")
    public ResponseEntity<SkillEntityView> upload(@RequestParam("file") MultipartFile file) {
        return ResponseEntity.ok(skillService.upload(file));
    }

    /**
     * 删除指定技能。
     *
     * <p>从注册中心和持久化存储中完全移除该技能。
     *
     * @param skillId 技能唯一标识
     * @return 204 No Content（成功删除）
     */
    @DeleteMapping("/{skillId}")
    public ResponseEntity<Void> delete(@PathVariable String skillId) {
        skillService.delete(skillId);
        return ResponseEntity.noContent().build();
    }
}
