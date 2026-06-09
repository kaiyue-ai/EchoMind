package com.echomind.agent.tool.router;

import com.echomind.agent.tool.core.Tool;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 统一工具注册表。
 *
 * <p>它只维护本地 Skill 与 MCP 工具的运行时索引，不再根据关键词或 Agent 绑定关系
 * 预筛选工具。普通聊天会把所有已启用工具交给 LLM，由模型结合工具描述和 schema
 * 自主决定是否调用。</p>
 */
@Slf4j
public class ToolRouter {

    protected final Map<String, Tool> tools = new ConcurrentHashMap<>();

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
}
