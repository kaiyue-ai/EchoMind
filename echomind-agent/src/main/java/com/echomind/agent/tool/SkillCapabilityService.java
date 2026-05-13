package com.echomind.agent.tool;

import com.echomind.common.model.SkillState;
import com.echomind.skill.registry.SkillRegistration;
import com.echomind.skill.registry.SkillRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Skill能力暴露服务。
 *
 * <p>它是Skill运行态和统一能力注册中心之间的唯一桥梁：
 * {@link SkillRegistry}只维护Skill状态，{@link CapabilityRegistry}负责给Agent提供工具视图。
 * MCP视图只保留外部MCP Server挂载进来的工具，避免“本地Skill自动暴露成MCP服务端工具”。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class SkillCapabilityService {

    private final SkillRegistry skillRegistry;
    private final CapabilityRegistry capabilityRegistry;

    /**
     * 启用Skill，并同步暴露为Agent工具。
     *
     * @param skillId Skill的唯一标识符
     */
    public void enable(String skillId) {
        skillRegistry.enable(skillId);
        skillRegistry.getRegistration(skillId)
            .filter(reg -> reg.getState() == SkillState.ENABLED)
            .ifPresent(this::registerExposure);
    }

    /**
     * 禁用Skill，并从所有能力视图中移除。
     *
     * @param skillId Skill的唯一标识符
     */
    public void disable(String skillId) {
        unregisterExposure(skillId);
        skillRegistry.disable(skillId);
        unregisterExposure(skillId);
    }

    /**
     * 同步当前所有已启用Skill。
     *
     * <p>启动期热加载完成后调用，用于一次性建立Agent工具视图。</p>
     */
    public void syncEnabledSkills() {
        int count = 0;
        for (SkillRegistration registration : skillRegistry.listAll()) {
            if (registration.getState() == SkillState.ENABLED) {
                registerExposure(registration);
                count++;
            }
        }
        log.info("Synchronized {} enabled skills into capability registry", count);
    }

    /**
     * 从所有能力视图中移除Skill暴露。
     *
     * @param skillId Skill的唯一标识符
     */
    public void unregisterExposure(String skillId) {
        capabilityRegistry.unregisterBySourceId(skillId);
    }

    private void registerExposure(SkillRegistration registration) {
        String skillId = registration.getSkillId();
        capabilityRegistry.register(new SkillToolAdapter(
            registration.getSkill(),
            skillId,
            skillRegistry
        ));
        log.info("Skill capability synchronized: {}", skillId);
    }
}
