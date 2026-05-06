package com.echomind.skill.api;

/**
 * <h2>技能执行结果</h2>
 * 封装一次技能执行的产出：成功时的输出文本，失败时的错误信息，以及执行耗时。
 *
 * <h3>成功/失败语义</h3>
 * <p>
 * {@code output} 和 {@code error} 两字段互斥：
 * </p>
 * <ul>
 *   <li><b>成功</b> —— {@code output} 包含执行结果（JSON 字符串或纯文本），{@code error} 为 null</li>
 *   <li><b>失败</b> —— {@code error} 包含错误描述和诊断信息，{@code output} 为 null</li>
 * </ul>
 *
 * <h3>工厂方法</h3>
 * <p>
 * 提供 {@code success()} 和 {@code failure()} 两个静态工厂方法以简化创建：
 * </p>
 * <pre>{@code
 * // 成功场景
 * return SkillResult.success(jsonResult, System.currentTimeMillis() - start);
 *
 * // 失败场景
 * return SkillResult.failure("查询天气失败: API key 无效", elapsed);
 * }</pre>
 *
 * <h3>elapsedMs 字段</h3>
 * 记录从收到请求到返回结果的墙钟时间（毫秒），用于性能监控和超时分析。
 * 此值由技能实现方自行计算——平台期望技能在 {@code execute()} 方法开始和结束时分别记录时间戳。
 *
 * @see Skill#execute(SkillRequest) 生成此结果的技能执行方法
 * @see com.echomind.common.model.ToolCall common 模块中对应的工具调用结果
 */
public record SkillResult(
    /**
     * 执行成功的输出文本。
     * 通常为 JSON 格式的结构化数据，也可以是纯文本描述。
     * 成功时此字段非 null，失败时为 null。
     */
    String output,
    /**
     * 执行失败时的错误信息。
     * 应包含人类可读的错误描述（中文或英文），可选附带异常类型和堆栈信息。
     * 成功时此字段为 null。
     */
    String error,
    /**
     * 执行耗时（毫秒）。
     * 从 {@code execute()} 方法开始处理到返回结果的墙钟耗时，由技能实现方自行测量。
     * 用于性能分析、SLA 监控和超时告警。
     */
    long elapsedMs
) {
    /**
     * 创建一个成功的执行结果。
     *
     * @param output    技能产出的文本/JSON 结果
     * @param elapsedMs 执行耗时（毫秒）
     * @return 带有输出文本和耗时的成功结果，{@code error} 为 null
     */
    public static SkillResult success(String output, long elapsedMs) {
        return new SkillResult(output, null, elapsedMs);
    }

    /**
     * 创建一个失败的执行结果。
     *
     * @param error     错误描述信息（人类可读）
     * @param elapsedMs 执行耗时（毫秒），即使失败也记录到出错为止的耗时
     * @return 带有错误信息和耗时的失败结果，{@code output} 为 null
     */
    public static SkillResult failure(String error, long elapsedMs) {
        return new SkillResult(null, error, elapsedMs);
    }

    /**
     * 判断本次执行是否成功。
     * 判断依据是 {@code error} 字段是否为 null。
     *
     * @return {@code true} 表示成功（error == null），{@code false} 表示失败
     */
    public boolean isSuccess() {
        return error == null;
    }
}
