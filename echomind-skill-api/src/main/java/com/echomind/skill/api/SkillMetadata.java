package com.echomind.skill.api;

import java.util.Collections;
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
 * record 的紧凑构造函数对 {@code parameterSchema}、{@code dependencies} 和 {@code tags}
 * 三个集合类字段进行了空值防护，将 null 替换为空集合，避免下游出现 NPE。
 *
 * @see Skill#metadata() 技能接口中返回此元数据的方法
 * @see com.echomind.common.model.SkillMetadata 通用模型层的对应类型
 */
public record SkillMetadata(
    /**
     * 技能名称，简短标识符，例如 {@code "weather"}、{@code "calculator"}。
     * 建议使用小写字母和连字符/下划线，避免空格和特殊符号。
     */
    String name,
    /**
     * 语义化版本号（SemVer），格式为 {@code MAJOR.MINOR.PATCH}，例如 {@code "2.1.0"}。
     * 平台依赖此字段进行多版本管理、兼容性检查和依赖解析。
     */
    String version,
    /**
     * 技能的自然语言描述，说明技能的功能、适用场景和使用限制。
     * 此文本可能注入到 LLM 的系统提示中，建议包含明确的使用指引和边界说明。
     */
    String description,
    /**
     * 技能执行所需的输入参数 Schema（JSON Schema 风格的 Map 结构）。
     * 应包含 {@code type: "object"}、{@code properties} 和可选的 {@code required} 字段。
     * 平台在调用 {@code execute()} 前会基于此 Schema 进行参数校验。
     */
    Map<String, Object> parameterSchema,
    /**
     * 当前技能所依赖的其他技能列表（使用 {@code name@version} 格式的 skillId）。
     * 依赖技能必须在当前技能启用之前完成加载。
     */
    List<String> dependencies,
    /**
     * 技能作者或组织名称，用于技能市场的归属展示和联系方式提示。
     */
    String author,
    /**
     * 技能标签列表，用于分类、搜索和过滤。
     * 建议标签包括领域关键词（如 {@code "weather"}、{@code "data"}、{@code "api"}）
     * 和能力关键词（如 {@code "text-generation"}、{@code "image-analysis"}）。
     */
    List<String> tags
) {
    /**
     * 紧凑构造函数：对集合类字段进行空值防护。
     * <p>
     * 当调用方传入 null 时，自动替换为不可变的空集合，确保下游代码无需进行 null 检查。
     * 该转换在 record 构造阶段完成，保证了字段在后续使用中始终为非 null 值。
     * </p>
     *
     * @param name             技能名称
     * @param version          版本号
     * @param description      技能描述
     * @param parameterSchema  参数 Schema（null 时自动变为空 Map）
     * @param dependencies     依赖列表（null 时自动变为空 List）
     * @param author           作者名称
     * @param tags             标签列表（null 时自动变为空 List）
     */
    public SkillMetadata {
        parameterSchema = parameterSchema == null ? Map.of() : parameterSchema;
        dependencies = dependencies == null ? List.of() : dependencies;
        tags = tags == null ? List.of() : tags;
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
