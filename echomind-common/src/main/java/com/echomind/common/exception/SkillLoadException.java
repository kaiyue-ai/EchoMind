package com.echomind.common.exception;

/**
 * <h2>技能加载异常</h2>
 * 当技能 JAR 的加载、解析或初始化过程中发生不可恢复的错误时抛出。
 *
 * <h3>触发场景</h3>
 * <ul>
 *   <li>JAR 文件损坏或格式不符合规范</li>
 *   <li>JAR Manifest 中缺少 {@code EchoMind-Skill-Class} 声明</li>
 *   <li>技能实现类无法通过 {@code SkillClassLoader} 加载（类定义不兼容）</li>
 *   <li>技能元数据解析失败（JSON Schema 格式错误）</li>
 *   <li>技能依赖的其他技能不存在或版本不匹配</li>
 * </ul>
 *
 * <h3>处理策略</h3>
 * 此异常被 {@code SkillRegistry} 捕获后，对应技能的状态将被标记为 {@link com.echomind.common.model.SkillState#ERROR}，
 * 并记录完整的堆栈信息供运维人员排查。不影响其他已成功加载的技能正常运行。
 *
 * @see com.echomind.skill.SkillJarLoader JAR 包加载器
 * @see com.echomind.skill.SkillClassLoader 隔离类加载器
 * @see com.echomind.common.model.SkillState#ERROR 错误状态
 */
public class SkillLoadException extends EchoMindException {
    /**
     * 使用技能 ID 和原始异常构造加载异常。
     * 自动将 skillId 拼接到错误消息中，便于日志检索。
     *
     * @param skillId 加载失败的技能唯一标识（格式：{@code name@version}）
     * @param cause   导致加载失败的原始异常（例如 {@code ClassNotFoundException}）
     */
    public SkillLoadException(String skillId, Throwable cause) {
        super("Failed to load skill: " + skillId, cause);
    }

    /**
     * 使用自定义消息构造加载异常。
     * 适用于没有包装原始异常的场景（例如业务校验失败）。
     *
     * @param message 详细的错误描述消息
     */
    public SkillLoadException(String message) {
        super(message);
    }
}
