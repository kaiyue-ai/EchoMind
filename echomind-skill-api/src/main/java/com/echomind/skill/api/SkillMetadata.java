package com.echomind.skill.api;

import java.util.List;
import java.util.Map;

/**
 * <h2>技能元数据（SPI 契约层）</h2>
 * 描述一个 EchoMind 技能的静态身份信息和能力声明。此 record 属于 SPI 契约模块，
 * 是技能开发者必须填写的"技能身份证"。
 *
 * <h3>与 common 模块的 SkillMetadata 的关系</h3>
 * <p>
 * 本 record 与 {@code com.echomind.common.model.SkillMetadata} 在字段结构上保持一致，
 * 但分别服务于不同的层次：
 * </p>
 * <ul>
 *   <li><b>SPI 层（本类）</b> —— 零依赖设计，技能开发者直接引用此类型定义元数据</li>
 *   <li><b>通用模型层（common）</b> —— 平台内部模块间传递使用，可附加 Jackson 等序列化注解</li>
 * </ul>
 * <p>
 * 平台在加载技能时会进行两层模型之间的转换，确保技能开发者无需接触平台内部依赖。
 * </p>
 *
 * <h3>紧凑构造函数</h3>
 * record 的紧凑构造函数对 {@code parameterSchema}、{@code dependencies}、{@code tags}、
 * {@code keywords} 和 {@code aliases} 进行了空值防护，将 null 替换为空集合，避免下游出现 NPE。
 *
 * @see Skill#metadata() 技能接口中返回此元数据的方法
 * @see com.echomind.common.model.SkillMetadata 通用模型层的对应类型
 */
public record SkillMetadata(
   // skill名称
    String name,
   // 版本
    String version,
   // 描述
    String description,
  // 参数
    Map<String, Object> parameterSchema,
   // 所需要依赖的其他的skill
    List<String> dependencies,
    // 作者
    String author,
    // 标签
    List<String> tags,
    // 关键词
    List<String> keywords,
   // 别名
    Map<String, List<String>> aliases
) {

    public SkillMetadata {
        parameterSchema = parameterSchema == null ? Map.of() : parameterSchema;
        dependencies = dependencies == null ? List.of() : dependencies;
        tags = tags == null ? List.of() : tags;
        keywords = keywords == null ? List.of() : keywords;
        aliases = aliases == null ? Map.of() : aliases;
    }

    /**
     * 兼容旧版 Skill JAR 的构造器。
     *
     * <p>旧 JAR 的字节码会调用 7 参数构造器。保留此构造器后，即使平台升级了
     * {@code keywords/aliases} 字段，旧 JAR 也可以继续加载和执行。</p>
     */
    public SkillMetadata(String name, String version, String description,
                         Map<String, Object> parameterSchema, List<String> dependencies,
                         String author, List<String> tags) {
        this(name, version, description, parameterSchema, dependencies, author, tags, List.of(), Map.of());
    }

    /**
     * 生成技能的唯一标识符。
     * 规则为 {@code name + "@" + version}，例如 {@code "calculator@1.0.0"}。
     * <p>
     * 该标识符在 EchoMind 平台上全局唯一，用于技能注册、路由查找、依赖解析和日志追踪。
     * </p>
     *
     * @return 格式为 {@code 技能名@版本号} 的唯一技能 ID
     */
    public String skillId() {
        return name + "@" + version;
    }
}
