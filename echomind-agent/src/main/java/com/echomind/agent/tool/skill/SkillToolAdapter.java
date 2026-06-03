package com.echomind.agent.tool.skill;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.tool.core.Tool;
import com.echomind.agent.tool.core.ToolResult;
import com.echomind.common.observability.EchoMindTrace;
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

import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;

// 这是一个适配器,将skill包装成Tool的模型然后注册到registry里面
public class SkillToolAdapter implements Tool {

    // 执行skill的线程池
    private static final ExecutorService executor = new ThreadPoolExecutor(
        4, 16, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(200),
        new ThreadPoolExecutor.CallerRunsPolicy());

    private final Skill skill;
    private final String skillId;
    private final SkillRegistry registry; // 只是检查这个skill是否在启用状态
    private final SkillMetadata meta;

    public SkillToolAdapter(Skill skill, String skillId, SkillRegistry registry) {
        this.skill = skill;
        this.skillId = skillId;
        this.registry = registry;
        this.meta = skill.metadata();
    }

    @Override // 获取
    public String name() { return meta.name(); }

    @Override // 获取描述
    public String description() { return meta.description(); }

    @Override // 获取参数列表
    public Map<String, Object> parameterSchema() { return meta.parameterSchema(); }

    @Override // 获取标签
    public List<String> tags() {
        return meta.tags();
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
    public String sourceType() { return Tool.SOURCE_SKILL; }

    @Override
    public String sourceId() { return skillId; }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters) {
        return execute(parameters, null);
    }

    @Override
    public CompletableFuture<ToolResult> execute(Map<String, Object> parameters, PipelineContext context) {
        // 捕获调用线程的 OpenTelemetry 上下文，异步线程中恢复，保证 trace 链路不断
        Context otelContext = Context.current();
        return CompletableFuture.supplyAsync(() -> {
            try (Scope ignored = otelContext.makeCurrent()) {
                long start = System.currentTimeMillis();
                try {
                    // 双重保险：即使工具仍留在 CapabilityRegistry，执行前再检查 Skill 是否已被禁用
                    if (registry != null && !registry.isEnabled(skillId, skill)) {
                        return ToolResult.failure("Skill is disabled: " + skillId,
                            System.currentTimeMillis() - start);
                    }
                    // PipelineContext → SkillContext：将管线上下文转换为 Skill 所需的上下文
                    // sessionAttributes 就是 PipelineContext.attributes 的透传
                    SkillContext skillContext = context == null ? null : new SkillContext(
                        context.getSessionId(),
                        context.getAgentId(),
                        context.getAttributes()
                    );
                    SkillRequest request = new SkillRequest(parameters, skillContext, null);
                    // 调用 Skill 业务逻辑并阻塞等待结果
                    SkillResult result = skill.execute(request).join();
                    long duration = System.currentTimeMillis() - start;
                    if (result.isSuccess()) {
                        return ToolResult.success(result.output(), duration);
                    } else {
                        return ToolResult.failure(result.error(), duration);
                    }
                } catch (Exception e) {
                    // 将异常记录到当前 trace span，方便在 Jaeger 中排查
                    EchoMindTrace.recordException(EchoMindTrace.currentSpan(), e);
                    return ToolResult.failure(e.getMessage(), System.currentTimeMillis() - start);
                }
            }
        }, executor); // 在独立线程池中执行，不阻塞 LLM 调用线程
    }
}
