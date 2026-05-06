package com.echomind.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.Map;

/**
 * <h2>技能元数据（通用模型层）</h2>
 * 描述一个 EchoMind 技能（Skill）的静态身份信息和能力声明。
 *
 * <h3>设计定位</h3>
 * <p>
 * 此 record 属于 {@code echomind-common} 模块，用于在平台各层之间传递技能的基本信息。
 * 它与 {@code echomind-skill-api} 包下的 {@link com.echomind.skill.api.SkillMetadata}
 * 在结构上保持一致，分别服务于通用模型层和 SPI 契约层。
 * </p>
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li><b>name + version → skillId</b>：技能的唯一标识由"名称@版本号"组成，确保同一技能的多版本可以共存</li>
 *   <li><b>parameterSchema</b>：JSON Schema 格式的参数定义，描述技能执行时所需的输入结构</li>
 *   <li><b>dependencies</b>：技能所依赖的其他技能标识列表，用于加载顺序控制和依赖注入</li>
 *   <li><b>tags</b>：技能标签，用于在技能市场中按关键词分类和搜索</li>
 * </ul>
 *
 * @see com.echomind.skill.api.SkillMetadata SPI 层对应的技能元数据定义
 * @see com.echomind.skill.SkillRegistry 使用此模型进行技能注册和查询
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SkillMetadata(
    /**
     * 技能名称，通常是驼峰命名的简短标识，例如 {@code "weather"}、{@code "calculator"}。
     */
    String name,
    /**
     * 语义化版本号，格式为 {@code major.minor.patch}（如 {@code "1.0.0"}）。
     * 同一技能的多个版本可以同时存在于平台上，通过 skillId 进行区分。
     */
    String version,
    /**
     * 技能的自然语言描述，说明技能的功能和适用场景。
     * 用于在技能市场展示、LLM 上下文注入等场景。
     */
    String description,
    /**
     * 技能的输入参数 JSON Schema 定义（Map 形式）。
     * 描述该技能在执行时期望接收的参数字段名、类型、是否必填等约束信息。
     */
    Map<String, Object> parameterSchema,
    /**
     * 该技能所依赖的其他技能标识列表（使用 skillId 格式）。
     * 依赖技能必须在当前技能之前完成加载和初始化。
     */
    List<String> dependencies,
    /**
     * 技能的开发者或组织名称，用于技能市场中的人类可读归属标注。
     */
    String author,
    /**
     * 技能的分类标签列表，例如 {@code ["data", "weather", "api"]}。
     * 为技能市场的前端搜索和后端过滤提供索引依据。
     */
    List<String> tags
) {
    /**
     * 生成技能的唯一标识符。
     * 规则为 {@code name + "@" + version}，形如 {@code "weather@1.2.0"}。
     * 该格式确保同名技能的不同版本拥有互不冲突的唯一 ID，平台路由和依赖解析均以此为依据。
     *
     * @return 技能唯一标识字符串，格式：{@code 技能名@版本号}
     */
    public String skillId() {
        return name + "@" + version;
    }
}
