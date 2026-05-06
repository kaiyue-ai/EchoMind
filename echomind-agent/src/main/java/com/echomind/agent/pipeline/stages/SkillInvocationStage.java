package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.skill.api.SkillContext;
import com.echomind.skill.api.SkillMetadata;
import com.echomind.skill.orchestrator.SkillOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

/**
 * Skill调用阶段——管道第三阶段（order=30），负责根据用户消息匹配合适的Skill并执行。
 *
 * <p>核心职责：</p>
 * <ul>
 *   <li>基于用户消息的多层匹配算法，自动发现并调用相关Skill</li>
 *   <li>将Skill执行结果收集到{@link PipelineContext#getSkillResults()}</li>
 *   <li>设置上下文属性标记是否触发了Skill</li>
 * </ul>
 *
 * <p>三层匹配算法（优先级依次降低）：</p>
 * <ol>
 *   <li><b>名称关键词匹配</b>：将Skill名称按{@code -}拆分为关键词，检查用户消息是否包含</li>
 *   <li><b>标签匹配</b>：检查用户消息是否包含Skill元数据中定义的任一标签</li>
 *   <li><b>描述关键词匹配</b>：将Skill描述文本分词后（按空格和中文标点分割），检查是否有长度>=2的词匹配</li>
 * </ol>
 *
 * <p>设计考量：</p>
 * <ul>
 *   <li>使用短路评估——一旦某层匹配成功，跳过后续层</li>
 *   <li>多个Skill可能同时匹配，全部依次执行</li>
 *   <li>单个Skill失败不会阻止其他Skill的执行</li>
 *   <li>后续可升级为LLM驱动的语义路由以获得更精准的匹配</li>
 * </ul>
 *
 * @author EchoMind Team
 * @see SkillOrchestrator
 */
public class SkillInvocationStage implements PipelineStage {

    /** SLF4J日志记录器，用于记录Skill调用事件和异常 */
    private static final Logger log = LoggerFactory.getLogger(SkillInvocationStage.class);

    /** Skill编排器，负责Skill的异步执行调度 */
    private final SkillOrchestrator orchestrator;

    /**
     * 构造Skill调用阶段。
     *
     * @param orchestrator Skill编排器
     */
    public SkillInvocationStage(SkillOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * @return 30 — 管道第三阶段，在模型解析之后、结果聚合之前执行
     */
    @Override
    public int order() { return 30; }

    /**
     * 匹配并执行相关Skill。
     *
     * <p>对每个已启用的Skill执行三层匹配算法，找到匹配后通过
     * {@link SkillOrchestrator#execute(String, Map, SkillContext)}异步调用Skill，
     * 并通过{@code .join()}同步等待结果。</p>
     *
     * <p>完成所有Skill调用后，设置{@code skillsInvoked}属性标记是否有Skill被触发，
     * 供{@code ResultAggregationStage}判断是否需要将Skill结果合成到最终响应中。</p>
     *
     * @param ctx 管道上下文，包含用户消息
     * @return 更新了skillResults和attributes的管道上下文
     */
    @Override
    public PipelineContext process(PipelineContext ctx) {
        SkillContext skillCtx = new SkillContext(ctx.getSessionId(), ctx.getAgentId(), Map.of());
        String userMsg = ctx.getUserMessage().toLowerCase();

        for (var skill : orchestrator.listAvailableSkills()) {
            String skillName = skill.metadata().name().toLowerCase();
            var meta = skill.metadata();

            boolean match = false;

            // 1. 匹配技能名称关键词（按 "-" 拆分）
            for (String keyword : skillName.split("-")) {
                if (userMsg.contains(keyword)) {
                    match = true;
                    break;
                }
            }

            // 2. 匹配标签（中英文通用）
            if (!match && meta.tags() != null) {
                for (String tag : meta.tags()) {
                    if (userMsg.contains(tag.toLowerCase())) {
                        match = true;
                        break;
                    }
                }
            }

            // 3. 匹配描述关键词
            if (!match && meta.description() != null) {
                for (String word : meta.description().toLowerCase().split("[\\s，。！？,.!?]+")) {
                    if (word.length() >= 2 && userMsg.contains(word)) {
                        match = true;
                        break;
                    }
                }
            }

            if (match) {
                log.info("Invoking skill: {} for message", skill.metadata().name());
                try {
                    // 根据 skill 的 parameter schema 构建参数
                    Map<String, Object> params = buildParams(meta, ctx.getUserMessage());
                    var result = orchestrator.execute(skillName, params, skillCtx);
                    String output = result.join().output();
                    ctx.getSkillResults().add("[" + skill.metadata().name() + "]: " + output);
                } catch (Exception e) {
                    log.error("Skill invocation failed: {}", e.getMessage());
                    ctx.getSkillResults().add("[" + skill.metadata().name() + "]: Error - " + e.getMessage());
                }
            }
        }

        ctx.getAttributes().put("skillsInvoked", !ctx.getSkillResults().isEmpty());
        return ctx;
    }

    /**
     * 根据技能的参数 Schema 构建调用参数 —— 智能提取用户消息中的参数值。
     *
     * <p>提取策略（按优先级）：
     * <ol>
     *   <li><b>枚举匹配</b>：若参数有 enum 约束，从消息中匹配枚举值（支持中英文关键词映射）</li>
     *   <li><b>路径提取</b>：若参数名为 path，用正则从消息中提取文件路径模式</li>
     *   <li><b>全文兜底</b>：将完整消息作为参数值</li>
     * </ol>
     *
     * @param meta    技能的元数据（包含 parameterSchema）
     * @param message 用户的原始消息
     * @return 构建好的参数映射，供 {@link SkillOrchestrator#execute} 使用
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildParams(SkillMetadata meta, String message) {
        Map<String, Object> params = new java.util.HashMap<>();
        Map<String, Object> schema = meta.parameterSchema();
        if (schema == null) {
            params.put("query", message);
            return params;
        }

        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        if (properties != null) {
            for (var entry : properties.entrySet()) {
                String key = entry.getKey();
                Map<String, Object> propDef = (Map<String, Object>) entry.getValue();

                // 1. 枚举类型参数 —— 从消息中匹配枚举值
                Object enumObj = propDef.get("enum");
                if (enumObj instanceof List<?> enumValues) {
                    String matched = matchEnum(message, enumValues);
                    if (matched != null) {
                        params.put(key, matched);
                        continue;
                    }
                }

                // 2. 路径参数 —— 用正则提取文件路径
                if (key.equalsIgnoreCase("path")) {
                    String extractedPath = extractPath(message);
                    if (extractedPath != null) {
                        params.put(key, extractedPath);
                        continue;
                    }
                }

                // 3. 默认：使用完整消息
                params.put(key, message);
            }
        }

        // 确保 required 中的第一个参数存在
        Object required = schema.get("required");
        if (required instanceof List && !((List<?>) required).isEmpty()) {
            String primary = String.valueOf(((List<?>) required).get(0));
            params.putIfAbsent(primary, message);
        }

        if (params.isEmpty()) {
            params.put("query", message);
        }
        return params;
    }

    /**
     * 从用户消息中匹配枚举值 —— 支持中英文关键词映射。
     *
     * <p>内置常用操作的中文别名映射表（如 "读取"→"read"、"计算"→"calculate"），
     * 使得用户说"帮我读取文件"时能匹配到枚举值 "read"。
     *
     * @param message    用户消息（已转为小写）
     * @param enumValues 枚举值列表
     * @return 匹配到的枚举值字符串；无匹配时返回 null
     */
    private String matchEnum(String message, List<?> enumValues) {
        String lower = message.toLowerCase();
        // 常用操作的中文 → 英文映射
        Map<String, List<String>> chineseAliases = Map.of(
            "read", List.of("读取", "读", "查看", "阅读", "read", "看"),
            "write", List.of("写入", "写", "保存", "创建", "write", "存"),
            "list", List.of("列出", "列表", "目录", "显示所有", "list", "ls", "看看")
        );

        for (Object ev : enumValues) {
            String val = String.valueOf(ev).toLowerCase();
            // 直接匹配枚举值本身
            if (lower.contains(val)) return String.valueOf(ev);
            // 尝试中文关键词映射
            List<String> aliases = chineseAliases.get(val);
            if (aliases != null) {
                for (String alias : aliases) {
                    if (lower.contains(alias)) return String.valueOf(ev);
                }
            }
        }
        return null;
    }

    /**
     * 从用户消息中提取文件路径。
     *
     * <p>支持的路径格式：
     * <ul>
     *   <li>Unix 风格：{@code ./data/file.txt}、{@code /home/user/file}</li>
     *   <li>Windows 风格：{@code C:\Users\file.txt}</li>
     * </ul>
     *
     * @param message 用户消息
     * @return 匹配到的路径字符串；无匹配时返回 null
     */
    private String extractPath(String message) {
        Pattern pathPattern = Pattern.compile(
            "([.\\w/-]*(?:/[.\\w-]+)+" +    // Unix 路径：./data/file.txt
            "|[a-zA-Z]:[\\\\/][.\\w\\\\/-]+" + // Windows 路径：C:\Users\file.txt
            "|[.]/[.\\w/-]+)"                // 相对路径：./data/file
        );
        Matcher m = pathPattern.matcher(message);
        if (m.find()) return m.group();
        return null;
    }
}
