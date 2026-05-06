package com.echomind.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * <h2>JSON Schema 验证器</h2>
 * 对技能输入参数进行轻量级的 JSON Schema 校验，确保调用方传入的参数符合技能的参数定义。
 *
 * <h3>验证范围</h3>
 * <p>
 * 本验证器实现了 JSON Schema 规范的一个子集，专注于 EchoMind 技能场景中最关键的两项校验：
 * </p>
 * <ol>
 *   <li><b>必填字段校验</b> —— 检查 {@code required} 数组中声明的字段是否全部存在于输入中</li>
 *   <li><b>未知字段校验</b> —— 当 Schema 不允许 {@code additionalProperties} 时，
 *       检测输入中是否包含未在 {@code properties} 中声明的字段</li>
 * </ol>
 *
 * <h3>设计决策：非完整 JSON Schema 实现</h3>
 * <p>
 * 选择自行实现而非引入完整的 JSON Schema 校验库（如 {@code everit-json-schema} 或 {@code networknt/json-schema}），
 * 是基于以下考量：
 * </p>
 * <ul>
 *   <li>技能参数 Schema 通常结构简单，不需要完整的 Draft 2020-12 校验能力</li>
 *   <li>减少外部依赖，保持 {@code echomind-common} 模块的轻量化</li>
 *   <li>错误消息可以直接定制为中文，便于 LLM 将其反馈给终端用户</li>
 *   <li>类型校验（string/number/boolean 等）由 Jackson 在反序列化时自动完成</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <pre>{@code
 * Map<String, Object> schema = skill.metadata().parameterSchema();
 * Map<String, Object> input = request.parameters();
 * List<String> errors = JsonSchemaValidator.validate(schema, input);
 * if (!errors.isEmpty()) {
 *     // 将错误列表返回给 LLM 或终端用户
 * }
 * }</pre>
 *
 * @see com.echomind.skill.api.SkillMetadata#parameterSchema() 技能的参数 Schema 定义
 * @see com.echomind.skill.api.SkillRequest#parameters() 技能执行请求的输入参数
 */
public class JsonSchemaValidator {

    /**
     * 共享的 Jackson ObjectMapper 实例，用于将 Map 转换为 JsonNode 树结构。
     * 声明为 static final 以避免重复创建，提高性能。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 根据给定的 JSON Schema 定义验证输入参数。
     *
     * <h3>验证流程</h3>
     * <ol>
     *   <li>Schema 为空或 null 时直接返回空错误列表（无约束 = 无校验）</li>
     *   <li>遍历 {@code required} 列表，检查每个必填字段是否存在于输入中</li>
     *   <li>遍历输入的所有字段，检查是否都在 {@code properties} 中声明过
     *       （仅当未设置 {@code "additionalProperties": "*"} 时执行此检查）</li>
     * </ol>
     *
     * @param schema JSON Schema 定义（Map 形式），包含 {@code properties} 和可选的
     *               {@code required}、{@code additionalProperties} 键
     * @param input  待验证的输入参数（Map 形式）
     * @return 验证错误信息列表，每个元素为一条人类可读的中文错误描述；
     *         列表为空表示验证通过
     */
    public static List<String> validate(Map<String, Object> schema, Map<String, Object> input) {
        List<String> errors = new ArrayList<>();
        if (schema == null || schema.isEmpty()) {
            return errors;
        }

        JsonNode schemaNode = MAPPER.convertValue(schema, JsonNode.class);
        JsonNode inputNode = MAPPER.convertValue(input, JsonNode.class);

        Map<String, Object> properties = schema.get("properties") instanceof Map
            ? (Map<String, Object>) schema.get("properties") : Map.of();
        List<String> required = schema.get("required") instanceof List
            ? (List<String>) schema.get("required") : List.of();

        for (String field : required) {
            if (!input.containsKey(field)) {
                errors.add("Missing required field: " + field);
            }
        }

        for (var entry : input.entrySet()) {
            if (!properties.containsKey(entry.getKey()) && !"*".equals(schema.get("additionalProperties"))) {
                errors.add("Unknown field: " + entry.getKey());
            }
        }

        return errors;
    }
}
