package com.echomind.common.model;

/**
 * <h2>技能生命周期状态枚举</h2>
 * 描述一个 EchoMind 技能在整个生命周期中所处的状态阶段。
 *
 * <h3>状态流转逻辑</h3>
 * <pre>{@code
 *   (JAR 发现) → LOADED → ENABLED ←→ DISABLED
 *                    ↓        ↓
 *                  ERROR    UNLOADED
 * }</pre>
 * <ol>
 *   <li>技能 JAR 被发现后进入 {@link #LOADED} 状态（类加载完成、元数据已解析）</li>
 *   <li>调用 {@code onEnable()} 成功后进入 {@link #ENABLED} 状态（可接收执行请求）</li>
 *   <li>运行时可通过 {@code onDisable()} 切换到 {@link #DISABLED} 状态（暂停服务但不卸载类）</li>
 *   <li>JAR 文件被删除或主动卸载时进入 {@link #UNLOADED} 状态（类加载器被回收）</li>
 *   <li>任何阶段发生不可恢复错误时进入 {@link #ERROR} 状态</li>
 * </ol>
 *
 * <h3>线程安全</h3>
 * 状态切换由 {@code SkillRegistry} 内部使用 {@code AtomicReference} 或同步原语保证线程安全。
 *
 * @see com.echomind.skill.SkillRegistry 管理技能状态转换的核心注册中心
 * @see com.echomind.skill.api.Skill#onEnable() 技能启用回调
 * @see com.echomind.skill.api.Skill#onDisable() 技能停用回调
 */
public enum SkillState {
    /**
     * 已加载 —— 技能 JAR 的类已被 {@code SkillClassLoader} 加载，
     * 元数据已成功解析，但尚未调用 {@code onEnable()}。
     * 此状态下技能不可执行。
     */
    LOADED,
    /**
     * 已启用 —— {@code onEnable()} 已成功执行完毕，技能处于健康运行状态，
     * 可以接收并处理 {@link com.echomind.skill.api.SkillRequest}。
     */
    ENABLED,
    /**
     * 已停用 —— 技能的 {@code onDisable()} 已被调用，暂停接收新请求，
     * 但类加载器尚未卸载，可以在未来重新启用。
     * 正在执行中的请求不受影响，允许自然完成。
     */
    DISABLED,
    /**
     * 错误 —— 技能在加载或执行过程中发生了不可恢复的错误。
     * 该状态下的技能不会接收任何请求，需要人工干预或自动恢复机制介入。
     */
    ERROR,
    /**
     * 已卸载 —— 技能的类加载器已被垃圾回收或 JAR 文件已从技能目录中移除。
     * 此状态为终态，除非技能 JAR 被重新放入目录触发新一轮的加载流程。
     */
    UNLOADED
}
