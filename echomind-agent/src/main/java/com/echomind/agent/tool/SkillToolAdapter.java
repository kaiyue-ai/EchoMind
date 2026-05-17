package com.echomind.agent.tool;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.skill.api.Skill;
import com.echomind.skill.api.SkillContext;
import com.echomind.skill.api.SkillMetadata;
import com.echomind.skill.api.SkillRequest;
import com.echomind.skill.api.SkillResult;
import com.echomind.skill.registry.SkillRegistry;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import lombok.Getter;

/**
 * 将 EchoMind 本地 {@link Skill} 适配为 Agent 管线内部统一的 {@link Tool}。
 */
public class SkillToolAdapter implements Tool {

    private static final ExecutorService executor = new ThreadPoolExecutor(
        4, 16, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(200),
        new ThreadPoolExecutor.CallerRunsPolicy());

    @Getter
    private final Skill skill;
    @Getter
    private final String skillId;
    private final SkillRegistry registry;
    private final SkillMetadata meta;

    public SkillToolAdapter(Skill skill, String skillId) {
        this(skill, skillId, null);
    }

    public SkillToolAdapter(Skill skill, String skillId, SkillRegistry registry) {
        this.skill = skill;
        this.skillId = skillId;
        this.registry = registry;
        this.meta = skill.metadata();
    }

    @Override
    public String name() { return meta.name(); }

    @Override
    public String description() { return meta.description(); }

    @Override
    public Map<String, Object> parameterSchema() { return meta.parameterSchema(); }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> tags() {
        Object tagsObj = meta.tags();
        if (tagsObj instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }

    @Override
    public List<String> keywords() {
        return meta.keywords();
    }

    @Override
    public Map<String, List<String>> aliases() {
        return meta.aliases();
    }

    @Override
    public String sourceType() { return "skill"; }

    @Override
    public String sourceId() { return skillId; }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return execute(parameters, null);
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters, PipelineContext context) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.currentTimeMillis();
            try {
                if (registry != null && !registry.isEnabled(skillId, skill)) {
                    return ToolResult.failure("Skill is disabled: " + skillId,
                        System.currentTimeMillis() - start);
                }
                SkillContext skillContext = context == null ? null : new SkillContext(
                    context.getSessionId(),
                    context.getAgentId(),
                    context.getAttributes()
                );
                SkillRequest request = new SkillRequest(parameters, skillContext, null);
                SkillResult result = skill.execute(request).join();
                long duration = System.currentTimeMillis() - start;
                if (result.isSuccess()) {
                    return ToolResult.success(result.output(), duration);
                } else {
                    return ToolResult.failure(result.error(), duration);
                }
            } catch (Exception e) {
                return ToolResult.failure(e.getMessage(), System.currentTimeMillis() - start);
            }
        }, executor);
    }
}
