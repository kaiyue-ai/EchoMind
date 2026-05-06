package com.echomind.mcp.server;

import com.echomind.skill.api.Skill;
import com.echomind.skill.api.SkillMetadata;
import com.echomind.skill.api.SkillRequest;
import com.echomind.skill.api.SkillResult;

import java.util.List;
import java.util.Map;

/**
 * Skill 到 MCP ToolProvider 的适配器 —— 将 EchoMind Skill 包装为 MCP 工具提供者。
 *
 * <p>此适配器是 Skill 生态与 MCP 生态之间的核心桥梁：
 * <ul>
 *   <li>将 {@link Skill#metadata()} 中的 name、description、parameterSchema
 *       映射为 MCP 的 {@link ToolSpec}。</li>
 *   <li>将 {@link Skill#execute(SkillRequest)} 的调用结果（{@link SkillResult}）
 *       转换为 MCP 的 {@link ToolResult}。</li>
 * </ul>
 *
 * <p>使用此适配器后，所有 EchoMind Skill 都可以自动暴露为 MCP 工具，
 * 无需 Skill 开发者额外实现 {@link ToolProvider} 接口。
 *
 * <p>线程安全：Skill 的 execute 方法通过 CompletableFuture 异步执行，
 * 适配器使用 {@code .join()} 同步等待结果，调用方应确保在线程池中调用。
 *
 * @author EchoMind Team
 * @see ToolProvider
 * @see Skill
 * @since 1.0
 */
public class SkillToolProvider implements ToolProvider {

    /** 被适配的 Skill 实例 */
    private final Skill skill;
    /** Skill 的唯一标识符（如 "filesystem@1.0.0"） */
    private final String skillId;
    /** 缓存的工具规格列表（Skill 元数据不变，只构造一次） */
    private final List<ToolSpec> tools;

    /**
     * 构造 Skill 到 ToolProvider 的适配器。
     *
     * @param skill   要适配的 Skill 实例
     * @param skillId Skill 的唯一标识符（通常为 "name@version" 格式）
     */
    public SkillToolProvider(Skill skill, String skillId) {
        this.skill = skill;
        this.skillId = skillId;
        this.tools = buildToolSpecs(skill.metadata());
    }

    /**
     * 从 Skill 元数据构建 MCP 工具规格列表。
     *
     * <p>每个 Skill 映射为一个 MCP 工具，工具名使用 Skill 元数据中的 name 字段。
     *
     * @param meta Skill 元数据
     * @return 包含单个 ToolSpec 的列表
     */
    private List<ToolSpec> buildToolSpecs(SkillMetadata meta) {
        return List.of(new ToolSpec(
            meta.name(),
            meta.description(),
            meta.parameterSchema()
        ));
    }

    /**
     * 获取此适配器提供的 MCP 工具列表。
     *
     * @return 工具规格列表（构造时缓存，不可变）
     */
    @Override
    public List<ToolSpec> getTools() {
        return tools;
    }

    /**
     * 调用 Skill 并返回 MCP 格式的执行结果。
     *
     * <p>将 MCP 的参数 Map 包装为 {@link SkillRequest}，
     * 异步执行 Skill 后同步等待，将 {@link SkillResult} 转换为 {@link ToolResult}。
     *
     * @param toolName  工具名称（应与 Skill 元数据中的 name 一致）
     * @param arguments 工具调用参数
     * @return MCP 格式的工具执行结果
     */
    @Override
    public ToolResult call(String toolName, Map<String, Object> arguments) {
        try {
            SkillRequest request = new SkillRequest(arguments, null, null);
            SkillResult result = skill.execute(request).join();
            if (result.isSuccess()) {
                return ToolResult.success(result.output());
            } else {
                return ToolResult.error(result.error());
            }
        } catch (Exception e) {
            return ToolResult.error("Skill execution failed: " + e.getMessage());
        }
    }

    /** @return 被适配的 Skill 实例 */
    public Skill getSkill() { return skill; }
    /** @return Skill 的唯一标识符 */
    public String getSkillId() { return skillId; }
}
