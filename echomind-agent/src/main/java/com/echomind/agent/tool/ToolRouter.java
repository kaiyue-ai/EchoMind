package com.echomind.agent.tool;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一工具注册表和匹配器。
 *
 * <p>它把本地 Skill 与 MCP 工具放到同一个命名空间里，并通过工具名、标签、
 * 描述等信息从用户消息中匹配可能要调用的工具。</p>
 */
@Slf4j
public class ToolRouter {

    /** 达到这个分数才认为是明确关键词命中，可以收窄模型候选工具。 */
    private static final int STRONG_MATCH_SCORE = 4;

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();

    /**
     * 当前可路由工具集合。
     *
     * <p>默认使用本类内部Map；统一能力注册中心会覆写此方法，提供同一状态源下的Agent视图。</p>
     */
    protected Collection<Tool> currentTools() {
        return tools.values();
    }

    /** 标签和关键词的中文别名，用于提升自然语言命中率。 */
    private static final Map<String, List<String>> TAG_ALIASES = Map.of(
        "write", List.of("写入", "写", "保存", "创建"),
        "read", List.of("读取", "读", "查看", "阅读"),
        "search", List.of("搜索", "查询", "查找"),
        "file", List.of("文件", "文档"),
        "weather", List.of("天气", "气温"),
        "calculate", List.of("计算", "运算", "算"),
        "lookup", List.of("查询", "查找", "搜索"),
        "find", List.of("查找", "搜索", "找"),
        "web", List.of("网络", "网页", "上网", "在线")
    );

    /** 枚举参数的中文别名，用于把“读取/写入/列出”等中文动作映射到 schema 枚举值。 */
    private static final Map<String, List<String>> ENUM_ALIASES = Map.of(
        "read", List.of("读取", "读", "查看", "阅读", "read", "看"),
        "write", List.of("写入", "写", "保存", "创建", "write", "存"),
        "list", List.of("列出", "列表", "目录", "显示所有", "list", "ls", "看看")
    );

    /** 描述文本中常见但没有路由价值的英文停用词。 */
    private static final Set<String> STOP_WORDS = Set.of(
        "the", "and", "for", "with", "from", "that", "this", "you", "your", "are",
        "use", "used", "using", "into", "onto", "when", "need", "needs", "tool",
        "tools", "data", "text", "input", "output", "string", "object", "type"
    );

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
        log.info("Registered tool: {} (source={})", tool.name(), tool.sourceType());
    }

    public void registerAll(Collection<Tool> toolList) {
        for (Tool tool : toolList) {
            register(tool);
        }
    }

    public void unregister(String toolName) {
        Tool removed = tools.remove(toolName);
        if (removed != null) {
            log.info("Unregistered tool: {}", toolName);
        }
    }

    /**
     * 按来源ID注销工具，主要用于Skill启停时同步清理工具路由。
     *
     * <p>Skill工具的来源ID是完整skillId，例如calculator@1.0.0。
     * 使用来源ID可以避免不同来源的同名工具互相误删。</p>
     */
    public void unregisterBySourceId(String sourceId) {
        List<String> toolNames = tools.entrySet().stream()
            .filter(entry -> Objects.equals(entry.getValue().sourceId(), sourceId))
            .map(Map.Entry::getKey)
            .toList();
        for (String toolName : toolNames) {
            Tool removed = tools.remove(toolName);
            if (removed != null) {
                log.info("Unregistered tool: {} (sourceId={})", toolName, sourceId);
            }
        }
    }

    public Optional<Tool> get(String toolName) {
        return Optional.ofNullable(tools.get(toolName));
    }

    public Collection<Tool> listAll() {
        return List.copyOf(currentTools());
    }

    /**
     * 根据 Agent 配置过滤可暴露给模型的工具。
     *
     * <p>MCP 工具默认可见；Skill 工具需要命中 Agent 的 skillIds。为了兼容配置习惯，
     * 支持三种写法：工具名、完整 sourceId、去掉版本号后的 sourceId。</p>
     */
    public List<Tool> listForAgentSkillIds(Collection<?> allowedSkillIds) {
        List<Tool> allTools = new ArrayList<>(currentTools());
        if (allowedSkillIds == null || allowedSkillIds.isEmpty()) {
            return allTools;
        }

        return allTools.stream()
            .filter(tool -> !"skill".equals(tool.sourceType()) || isAllowedSkill(tool, allowedSkillIds))
            .toList();
    }

    /**
     * 先按Agent允许范围过滤，再做关键词匹配。
     *
     * <p>用于模型函数调用前的候选工具收窄：关键词命中时只把相关工具交给模型；
     * 未命中时再让模型在完整允许工具集合里智能判断。</p>
     */
    public List<Tool> matchForAgentSkillIds(String userMessage, Collection<?> allowedSkillIds) {
        List<Tool> allowedTools = listForAgentSkillIds(allowedSkillIds);
        if (userMessage == null || userMessage.isBlank()) {
            return List.of();
        }
        String lowerMsg = userMessage.toLowerCase();
        return allowedTools.stream()
            .map(tool -> new ToolMatch(tool, score(lowerMsg, tool)))
            .filter(match -> match.score() >= STRONG_MATCH_SCORE)
            .sorted(Comparator.comparingInt(ToolMatch::score).reversed())
            .map(ToolMatch::tool)
            .toList();
    }

    private boolean isAllowedSkill(Tool tool, Collection<?> allowedSkillIds) {
        String sourceId = tool.sourceId();
        if (allowedSkillIds.contains(sourceId) || allowedSkillIds.contains(tool.name())) {
            return true;
        }
        int versionSep = sourceId == null ? -1 : sourceId.indexOf('@');
        return versionSep > 0 && allowedSkillIds.contains(sourceId.substring(0, versionSep));
    }

    /**
     * 动态关键词匹配。
     *
     * <p>强匹配来源优先级：Skill显式 keywords/aliases、工具名、标签、平台通用别名。
     * 描述和参数 Schema 只提供低权重信号，避免新 JAR 只有泛化描述时误收窄候选。</p>
     */
    public List<Tool> match(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return List.of();
        }
        String lowerMsg = userMessage.toLowerCase();
        return currentTools().stream()
            .map(tool -> new ToolMatch(tool, score(lowerMsg, tool)))
            .filter(match -> match.score() >= STRONG_MATCH_SCORE)
            .sorted(Comparator.comparingInt(ToolMatch::score).reversed())
            .map(ToolMatch::tool)
            .toList();
    }

    private int score(String lowerMsg, Tool tool) {
        int score = 0;

        // 1. Skill JAR 显式声明的关键词和别名：最可信，命中后应优先收窄候选。
        score += scoreTerms(lowerMsg, tool.keywords(), 8);
        for (var entry : tool.aliases().entrySet()) {
            score += scoreTerm(lowerMsg, entry.getKey(), 6);
            score += scoreTerms(lowerMsg, entry.getValue(), 8);
        }

        // 2. 工具名和标签：来自元数据，适合新 JAR 自描述能力。
        score += scoreTerm(lowerMsg, tool.name(), 6);
        score += scoreTerms(lowerMsg, splitIdentifier(tool.name()), 4);
        for (String tag : tool.tags()) {
            score += scoreTerm(lowerMsg, tag, 5);
            score += scoreTerms(lowerMsg, TAG_ALIASES.get(tag.toLowerCase()), 5);
        }

        // 3. 参数 Schema：字段名、枚举值和字段描述能辅助匹配，但权重低于显式关键词。
        score += scoreTerms(lowerMsg, schemaTerms(tool.parameterSchema()), 2);

        // 4. 描述文本：只作为弱信号，多处命中才会达到强匹配阈值。
        score += scoreTerms(lowerMsg, textTerms(tool.description()), 2);

        return score;
    }

    private int scoreTerms(String lowerMsg, Collection<String> terms, int points) {
        if (terms == null || terms.isEmpty()) {
            return 0;
        }
        int score = 0;
        Set<String> seen = new HashSet<>();
        for (String term : terms) {
            String normalized = normalizeTerm(term);
            if (normalized != null && seen.add(normalized) && containsTerm(lowerMsg, normalized)) {
                score += points;
            }
        }
        return score;
    }

    private int scoreTerm(String lowerMsg, String term, int points) {
        String normalized = normalizeTerm(term);
        return normalized != null && containsTerm(lowerMsg, normalized) ? points : 0;
    }

    private boolean containsTerm(String lowerMsg, String term) {
        return lowerMsg.contains(term);
    }

    private String normalizeTerm(String term) {
        if (term == null) {
            return null;
        }
        String normalized = term.trim().toLowerCase();
        if (normalized.isEmpty() || STOP_WORDS.contains(normalized)) {
            return null;
        }
        if (containsCjk(normalized)) {
            return normalized.length() >= 1 ? normalized : null;
        }
        return normalized.length() >= 3 ? normalized : null;
    }

    private List<String> splitIdentifier(String text) {
        if (text == null) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        for (String part : text.split("[\\s_\\-./:]+")) {
            String normalized = normalizeTerm(part);
            if (normalized != null) {
                terms.add(normalized);
            }
        }
        return terms;
    }

    private List<String> textTerms(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        for (String part : text.toLowerCase().split("[\\s，。！？,.!?;；:：()（）\\[\\]{}<>]+")) {
            String normalized = normalizeTerm(part);
            if (normalized != null) {
                terms.add(normalized);
                if (containsCjk(normalized) && normalized.length() > 2) {
                    terms.addAll(cjkNgrams(normalized));
                }
            }
        }
        return terms;
    }

    @SuppressWarnings("unchecked")
    private List<String> schemaTerms(Map<String, Object> schema) {
        if (schema == null || schema.isEmpty()) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        Object propertiesObj = schema.get("properties");
        if (propertiesObj instanceof Map<?, ?> properties) {
            for (var entry : properties.entrySet()) {
                terms.add(String.valueOf(entry.getKey()));
                Object propObj = entry.getValue();
                if (propObj instanceof Map<?, ?> propDef) {
                    Object description = propDef.get("description");
                    if (description != null) {
                        terms.addAll(textTerms(String.valueOf(description)));
                    }
                    Object enumObj = propDef.get("enum");
                    if (enumObj instanceof List<?> enumValues) {
                        for (Object enumValue : enumValues) {
                            terms.add(String.valueOf(enumValue));
                            List<String> aliases = ENUM_ALIASES.get(String.valueOf(enumValue).toLowerCase());
                            if (aliases != null) {
                                terms.addAll(aliases);
                            }
                        }
                    }
                }
            }
        }
        Object schemaDescription = schema.get("description");
        if (schemaDescription != null) {
            terms.addAll(textTerms(String.valueOf(schemaDescription)));
        }
        return terms;
    }

    private boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            Character.UnicodeScript script = Character.UnicodeScript.of(text.charAt(i));
            if (script == Character.UnicodeScript.HAN) {
                return true;
            }
        }
        return false;
    }

    private List<String> cjkNgrams(String text) {
        List<String> terms = new ArrayList<>();
        for (int size = 2; size <= 3; size++) {
            for (int i = 0; i + size <= text.length(); i++) {
                terms.add(text.substring(i, i + size));
            }
        }
        return terms;
    }

    private record ToolMatch(Tool tool, int score) {}

    /**
     * 根据工具参数 schema，从用户消息里抽取可用参数。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> buildParams(Tool tool, String userMessage) {
        Map<String, Object> params = new HashMap<>();
        Map<String, Object> schema = tool.parameterSchema();
        if (schema == null) {
            params.put("query", userMessage);
            return params;
        }

        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        if (properties != null) {
            for (var entry : properties.entrySet()) {
                String key = entry.getKey();
                Map<String, Object> propDef = (Map<String, Object>) entry.getValue();

                // 枚举参数匹配
                Object enumObj = propDef.get("enum");
                if (enumObj instanceof List<?> enumValues) {
                    String matched = matchEnum(userMessage.toLowerCase(), enumValues);
                    if (matched != null) {
                        params.put(key, matched);
                        continue;
                    }
                }

                // 文件路径提取
                if (key.equalsIgnoreCase("path")) {
                    String extractedPath = extractPath(userMessage);
                    if (extractedPath != null) {
                        if (!extractedPath.contains("/") && !extractedPath.contains("\\")
                            && !extractedPath.contains(":")) {
                            extractedPath = "./data/" + extractedPath;
                        }
                        params.put(key, extractedPath);
                    } else {
                        params.put(key, "./data/");
                    }
                    continue;
                }

                // 内容或查询词提取
                if (key.equalsIgnoreCase("content") || key.equalsIgnoreCase("query")) {
                    String extracted = extractContent(userMessage);
                    if (extracted != null) {
                        params.put(key, extracted);
                        continue;
                    }
                }

                params.put(key, userMessage);
            }
        }

        // 确保 required 中的第一个参数一定有值。
        Object required = schema.get("required");
        if (required instanceof List && !((List<?>) required).isEmpty()) {
            String primary = String.valueOf(((List<?>) required).get(0));
            params.putIfAbsent(primary, userMessage);
        }

        if (params.isEmpty()) {
            params.put("query", userMessage);
        }
        return params;
    }

    private String matchEnum(String lowerMsg, List<?> enumValues) {
        for (Object ev : enumValues) {
            String val = String.valueOf(ev).toLowerCase();
            if (lowerMsg.contains(val)) return String.valueOf(ev);
            List<String> aliases = ENUM_ALIASES.get(val);
            if (aliases != null) {
                for (String alias : aliases) {
                    if (lowerMsg.contains(alias)) return String.valueOf(ev);
                }
            }
        }
        return null;
    }

    private String extractPath(String message) {
        java.util.regex.Pattern pathPattern = java.util.regex.Pattern.compile(
            "([a-zA-Z]:[\\\\/][.\\w\\\\/-]+|[.~/][.\\w/-]*|[\\w.-]+\\.[a-zA-Z]{1,6})");
        java.util.regex.Matcher m = pathPattern.matcher(message);
        return m.find() ? m.group() : null;
    }

    private String extractContent(String message) {
        String[] prefixes = {"内容是", "内容为", "content:", "content=", "content：", "内容是：",
                            "搜索", "搜索：", "query:", "query=", "查询", "查找", "搜索一下",
                            "内容:", "内容：", "写入:", "写入：", "写入"};
        for (String prefix : prefixes) {
            int idx = message.indexOf(prefix);
            if (idx >= 0) {
                String after = message.substring(idx + prefix.length()).trim();
                after = after.replaceFirst("^[：:，,\\s]+", "");
                if (!after.isEmpty()) return after;
            }
        }
        String[] endMarkers = {"内容", "content", "写入", "搜索"};
        for (String marker : endMarkers) {
            int idx = message.lastIndexOf(marker);
            if (idx >= 0) {
                String after = message.substring(idx + marker.length()).trim();
                after = after.replaceFirst("^[是为:：,，\\s]+", "");
                if (after.length() > 1) return after;
            }
        }
        return null;
    }

    /** 按工具名执行工具。 */
    public CompletableFuture<ToolResult> execute(String toolName, Map<String, Object> params) {
        Tool tool = get(toolName).orElse(null);
        if (tool == null) {
            return CompletableFuture.completedFuture(
                ToolResult.failure("Tool not found: " + toolName, 0));
        }
        return tool.execute(params);
    }
}
