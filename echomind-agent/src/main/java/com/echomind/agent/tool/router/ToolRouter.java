package com.echomind.agent.tool.router;

import com.echomind.agent.tool.core.Tool;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一工具注册表和匹配器。
 *
 * <p>它把本地 Skill 与 MCP 工具放到同一个命名空间里，并通过工具名、标签、
 * keywords 和 aliases 从用户消息中匹配可能要调用的工具。</p>
 */
@Slf4j
public class ToolRouter {

    protected final Map<String, Tool> tools = new ConcurrentHashMap<>();
    private final ToolMatchScorer matchScorer = new ToolMatchScorer();

    /**
     * 当前可路由工具集合。
     *
     * <p>默认使用本类内部Map；统一能力注册中心会覆写此方法，提供同一状态源下的Agent视图。</p>
     */
    protected Collection<Tool> currentTools() {
        return tools.values();
    }

    public void register(Tool tool) {
        Tool existing = tools.put(tool.name(), tool);
        if (existing != null) {
            log.warn("Tool name conflict: '{}' already registered by {}/{}, overwritten by {}/{}",
                tool.name(), existing.sourceType(), existing.sourceId(),
                tool.sourceType(), tool.sourceId());
        } else {
            log.info("Registered tool: {} (source={})", tool.name(), tool.sourceType());
        }
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
            .filter(tool -> !Tool.SOURCE_SKILL.equals(tool.sourceType()) || isAllowedSkill(tool, allowedSkillIds))
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

    public List<Tool> matchTools(String userMessage, Collection<Tool> candidateTools) {
        if (userMessage == null || userMessage.isBlank()) {
            return List.of();
        }
        return matchScorer.match(userMessage, candidateTools);
    }

    private boolean isAllowedSkill(Tool tool, Collection<?> allowedSkillIds) {
        String sourceId = tool.sourceId();
        if (allowedSkillIds.contains(sourceId) || allowedSkillIds.contains(tool.name())) {
            return true;
        }
        int versionSep = sourceId == null ? -1 : sourceId.indexOf('@');
        return versionSep > 0 && allowedSkillIds.contains(sourceId.substring(0, versionSep));
    }
}