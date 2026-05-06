package com.echomind.skill.registry;

import com.echomind.common.model.SkillState;
import com.echomind.skill.api.Skill;
import com.echomind.skill.api.SkillMetadata;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * Skill注册记录，封装一个Skill在注册中心中的完整运行时信息。
 *
 * <p>每条注册记录包含：</p>
 * <ul>
 *   <li><b>skillId</b> — Skill的唯一标识符，在整个平台中唯一</li>
 *   <li><b>metadata</b> — Skill的静态元数据（名称、版本、描述、标签等）</li>
 *   <li><b>skill</b> — Skill实例本身，用于执行和生命周期回调</li>
 *   <li><b>classLoader</b> — 加载此Skill的隔离类加载器，用于后续卸载</li>
 *   <li><b>state</b> — Skill的运行时状态，支持线程间可见的原子更新</li>
 * </ul>
 *
 * <p>线程安全设计：</p>
 * <ul>
 *   <li>所有字段（除state外）在构造后不可变，保证线程安全</li>
 *   <li>state字段使用volatile修饰，确保多线程间的可见性</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see SkillRegistry
 */
public class SkillRegistration {

    /** Skill的唯一标识符，对应SkillMetadata.skillId()，在注册表中作为键 */
    private final String skillId;

    /** Skill的静态元数据，包含名称、版本、描述和依赖信息等 */
    private final SkillMetadata metadata;

    /** Skill的运行时实例，用于执行技能和生命周期管理 */
    private final Skill skill;

    /** 加载此Skill的隔离类加载器，确保Skill的类隔离和独立卸载 */
    private final ClassLoader classLoader;

    /**
     * Skill的当前运行时状态。
     *
     * <p>使用volatile确保多线程环境下的可见性。状态变更通常由
     * {@link SkillRegistry#register}, {@link SkillRegistry#enable},
     * {@link SkillRegistry#disable} 等方法触发。</p>
     */
    private volatile SkillState state;

    /**
     * 构造一个Skill注册记录。
     *
     * @param skillId     Skill的唯一标识符
     * @param metadata    Skill的静态元数据
     * @param skill       Skill运行时实例
     * @param classLoader 隔离类加载器
     * @param state       初始运行时状态
     */
    public SkillRegistration(String skillId, SkillMetadata metadata, Skill skill,
                             ClassLoader classLoader, SkillState state) {
        this.skillId = skillId;
        this.metadata = metadata;
        this.skill = skill;
        this.classLoader = classLoader;
        this.state = state;
    }

    /** @return Skill的唯一标识符 */
    public String getSkillId() { return skillId; }

    /** @return Skill的静态元数据 */
    public SkillMetadata getMetadata() { return metadata; }

    /** @return Skill运行时实例 */
    @JsonIgnore
    public Skill getSkill() { return skill; }

    /** @return 加载此Skill的隔离类加载器 */
    @JsonIgnore
    public ClassLoader getClassLoader() { return classLoader; }

    /** @return Skill的当前运行时状态 */
    public SkillState getState() { return state; }

    /**
     * 更新Skill的运行时状态。
     * @param state 新的运行时状态
     */
    public void setState(SkillState state) { this.state = state; }
}
