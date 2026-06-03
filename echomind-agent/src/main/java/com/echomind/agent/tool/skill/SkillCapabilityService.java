package com.echomind.agent.tool.skill;

import com.echomind.agent.tool.router.CapabilityRegistry;
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
// 就是说将skill包装成实现Tool接口的SkillAdapter对象然后注册到CapabilityRegistry中
@Slf4j
@RequiredArgsConstructor
public class SkillCapabilityService {

    private final SkillRegistry skillRegistry; // 这是skill的生命周期
    private final CapabilityRegistry capabilityRegistry; // skill和mcp的工具视图

    /**
     * 启用Skill，并同步暴露为Agent工具。
     *
     * @param skillId Skill的唯一标识符
     */
    public void enable(String skillId) {
        // 启用skill
        skillRegistry.enable(skillId);
        // 注册skill的暴露
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

    // 将skill包装成Tool,注册到CapabilityRegistry中
    private void registerExposure(SkillRegistration registration) {
        String skillId = registration.getSkillId();
        // 将skill包装成Tool,注册到CapabilityRegistry中
        capabilityRegistry.register(new SkillToolAdapter(
            registration.getSkill(), // 为什么使用registration而不是使用ski]
            //ll?因为像id或加载器状态等字段state是不具备的,所以还是需要一个包装这些信息的registration
            skillId, // skillId
            skillRegistry // 只是检查skill是否启用
        ));
        log.info("Skill capability synchronized: {}", skillId);
    }
}
