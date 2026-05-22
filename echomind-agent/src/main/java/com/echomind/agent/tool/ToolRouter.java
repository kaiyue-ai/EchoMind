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

    private final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final ToolCompatibilityPolicy compatibilityPolicy = new ToolCompatibilityPolicy();
    private final ToolDisambiguationPolicy disambiguationPolicy = new ToolDisambiguationPolicy();
    private final ToolMatchScorer matchScorer = new ToolMatchScorer(compatibilityPolicy);
    private final ToolParameterExtractor parameterExtractor = new ToolParameterExtractor();

    /**
     * 当前可路由工具集合。
     *
     * <p>默认使用本类内部Map；统一能力注册中心会覆写此方法，提供同一状态源下的Agent视图。</p>
     */
    protected Collection<Tool> currentTools() {
        return tools.values();
    }

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
        return matchTools(userMessage, listForAgentSkillIds(allowedSkillIds));
    }

    /**
     * 按用户消息过滤明显不兼容的工具。
     *
     * <p>典型场景是 URL：如果消息里是 CSDN/知乎链接，就不能把“只支持特定域名”的 MCP
     * 暴露给模型自由选择。这个过滤只处理确定性的域名边界，剩下仍交给关键词和模型判断。</p>
     */
    public List<Tool> filterCompatibleTools(String userMessage, Collection<Tool> candidateTools) {
        return compatibilityPolicy.filter(userMessage, candidateTools);
    }

    public List<Tool> matchTools(String userMessage, Collection<Tool> candidateTools) {
        if (userMessage == null || userMessage.isBlank()) {
            return List.of();
        }
        List<Tool> allowedTools = compatibilityPolicy.filter(userMessage, candidateTools);
        allowedTools = disambiguationPolicy.apply(userMessage, allowedTools);
        return matchScorer.match(userMessage, allowedTools);
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
        return matchTools(userMessage, currentTools());
    }

    /**
     * 根据工具参数 schema，从用户消息里抽取可用参数。
     */
    public Map<String, Object> buildParams(Tool tool, String userMessage) {
        return parameterExtractor.buildParams(tool, userMessage);
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
