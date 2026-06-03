package com.echomind.skill.registry;

import com.echomind.common.model.SkillState;
import com.echomind.skill.api.Skill;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

// skill注册表
@Slf4j
public class SkillRegistry {


    // 注册表
    private final Map<String, SkillRegistration> registrations = new ConcurrentHashMap<>();


    // 注册技能
    public void register(Skill skill, ClassLoader classLoader) {
        String skillId = skill.metadata().skillId();
        SkillRegistration reg = new SkillRegistration(
            skillId, skill.metadata(), skill, classLoader, SkillState.LOADED);
        registrations.put(skillId, reg);
        skill.onEnable();
        reg.setState(SkillState.ENABLED);
        log.info("Registered skill: {}", skillId);
    }


    // 解除注册
    public void unregister(String skillId) {
        SkillRegistration reg = registrations.remove(skillId);
        if (reg != null) {
            reg.getSkill().onDestroy();
            try {
                ClassLoader classLoader = reg.getClassLoader();
                if (classLoader instanceof AutoCloseable c) {
                    c.close();
                }
            } catch (Exception e) {
                log.warn("Failed to close class loader for skill {}: {}", skillId, e.getMessage());
            }
            log.info("Unregistered skill: {}", skillId);
        }
    }


    // 赋予能力
    public void enable(String skillId) {
        SkillRegistration reg = registrations.get(skillId);
        if (reg != null && reg.getState() != SkillState.ENABLED) {
            // 初始化
            reg.getSkill().onEnable();
            // 设置状态
            reg.setState(SkillState.ENABLED);
            log.info("Enabled skill: {}", skillId);
        }
    }


    public void disable(String skillId) {
        SkillRegistration reg = registrations.get(skillId);
        if (reg != null && reg.getState() == SkillState.ENABLED) {
            reg.getSkill().onDisable();
            reg.setState(SkillState.DISABLED);
            log.info("Disabled skill: {}", skillId);
        }
    }


    public Optional<Skill> getSkill(String skillId) {
        SkillRegistration reg = registrations.get(skillId);
        return (reg != null && reg.getState() == SkillState.ENABLED)
            ? Optional.of(reg.getSkill()) : Optional.empty();
    }


    public Optional<SkillRegistration> getRegistration(String skillId) {
        return Optional.ofNullable(registrations.get(skillId));
    }


    public boolean isEnabled(String skillId, Skill skill) {
        SkillRegistration reg = registrations.get(skillId);
        return reg != null
            && reg.getState() == SkillState.ENABLED
            && reg.getSkill() == skill;
    }


    public List<SkillRegistration> listAll() {
        return List.copyOf(registrations.values());
    }
}
