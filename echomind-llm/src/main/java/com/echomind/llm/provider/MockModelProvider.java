package com.echomind.llm.provider;

import com.echomind.common.model.TokenUsage;
import com.echomind.llm.provider.dto.ProviderRequest;
import com.echomind.llm.provider.dto.ProviderResponse;
import com.echomind.llm.router.ModelSpec;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MockModelProvider implements ModelProvider {

    /**
     * 关键词 → 响应 的映射表。
     * 线程安全，支持运行时动态添加预设响应。
     */
    private final Map<String, String> responses = new ConcurrentHashMap<>();

    /**
     * 注册一个关键词-响应对。
     *
     * <p>当后续的 {@link #chat} 调用中用户消息包含指定关键词时，
     * 将返回对应的预设响应。可用于测试特定对话场景。
     *
     * @param keyword  触发关键词（大小写敏感，包含匹配）
     * @param response 对应的预设响应文本
     */
    public void setResponse(String keyword, String response) {
        responses.put(keyword, response);
    }

    /**
     * 返回 Mock 提供商的标识符。
     *
     * @return 固定返回 "mock"
     */
    @Override
    public String providerId() {
        return "mock";
    }

    /**
     * 检查是否支持指定的模型规格。
     *
     * @param model 模型规格
     * @return 当 model.providerId() 为 "mock" 时返回 {@code true}
     */
    @Override
    public boolean supports(ModelSpec model) {
        return "mock".equals(model.providerId());
    }

    /**
     * 执行模拟对话 —— 按关键词匹配返回预设响应或回显用户输入。
     *
     * <p>遍历所有已注册的关键词，若用户消息包含某关键词则返回对应预设响应；
     * 若无匹配则回显用户消息。
     *
     * @param model        模型规格（Mock 模式下可忽略）
     * @param systemPrompt 系统提示词（Mock 模式下不使用）
     * @param userMessage  用户消息，用于关键词匹配
     * @return 匹配的预设响应，或 "[Mock] Echo: " + 用户消息
     */
    @Override
    public ProviderResponse chatWithUsage(ProviderRequest request) {
        String userMessage = request.userMessage();
        String teamResponse = teamPresetResponse(userMessage);
        if (teamResponse != null) {
            return new ProviderResponse(teamResponse, mockUsage(request, teamResponse));
        }
        if (request.hasTools()) {
            String response = "[Mock Tools] Would call tools for: " + userMessage;
            return new ProviderResponse(response, mockUsage(request, response));
        }
        for (var entry : responses.entrySet()) {
            if (userMessage.contains(entry.getKey())) {
                return new ProviderResponse(entry.getValue(), mockUsage(request, entry.getValue()));
            }
        }
        String response = "[Mock] Echo: " + userMessage;
        return new ProviderResponse(response, mockUsage(request, response));
    }

    /**
     * 模拟流式对话 —— 返回包含用户消息的单元素流。
     *
     * @param model        模型规格（Mock 模式下可忽略）
     * @param systemPrompt 系统提示词（Mock 模式下不使用）
     * @param userMessage  用户消息
     * @return 包含 "[Mock Stream] " + 用户消息 的单元素 Flux
     */
    @Override
    public Flux<ProviderStreamChunk> streamWithUsage(ProviderRequest request) {
        if (request.hasTools()) {
            String response = "[Mock Tools Stream] Would call tools for: " + request.userMessage();
            return Flux.just(ProviderStreamChunk.text(response), ProviderStreamChunk.usage(mockUsage(request, response)));
        }
        String response = "[Mock Stream] " + request.userMessage();
        return Flux.just(ProviderStreamChunk.text(response), ProviderStreamChunk.usage(mockUsage(request, response)));
    }

    private String teamPresetResponse(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return null;
        }
        if (userMessage.contains("You are the Planner in an Agent Team.")) {
            return """
                {"taskLevel":"COMPLEX","steps":[{"clientStepId":"step-1","title":"需求梳理","description":"梳理用户任务目标、约束和交付物边界。","dependsOn":[],"riskLevel":"LOW","riskReason":"","requiredCapabilities":["general"],"acceptanceCriteria":"列出目标、约束和可执行交付物。"},{"clientStepId":"step-2","title":"执行方案分析","description":"基于需求梳理结果提出执行要点和资源安排。","dependsOn":["step-1"],"riskLevel":"LOW","riskReason":"","requiredCapabilities":["general"],"acceptanceCriteria":"给出清晰的执行步骤和责任拆分。"},{"clientStepId":"step-3","title":"风险与收尾校验","description":"校验方案风险、依赖和最终交付注意事项。","dependsOn":["step-1"],"riskLevel":"LOW","riskReason":"","requiredCapabilities":["review"],"acceptanceCriteria":"列出主要风险和可执行补救动作。"}]}
                """.trim();
        }
        if (userMessage.contains("你是 Agent Team 的 AgentSelector")) {
            String memberKey = lastJsonString(userMessage, "memberKey", "mock-executor#20");
            String agentId = lastJsonString(userMessage, "agentId", "mock-executor");
            return "{\"memberKey\":\"" + jsonEscape(memberKey) + "\",\"agentId\":\""
                + jsonEscape(agentId) + "\",\"reason\":\"Mock 基于候选列表选择首个 Executor。\"}";
        }
        if (userMessage.contains("You are the Reviewer and quality gate for an Agent Team.")) {
            return "{\"action\":\"CONTINUE\",\"reason\":\"Mock 计划审查通过。\",\"questions\":[],\"retryStepIds\":[],\"revisionInstructions\":\"\",\"finalReport\":\"\"}";
        }
        if (userMessage.contains("你是 Agent Team 的 StepReviewer")) {
            return "{\"action\":\"PASS\",\"reason\":\"Mock Step 审查通过。\",\"questions\":[],\"retryStepIds\":[],\"revisionInstructions\":\"\",\"stepReflections\":{},\"finalReport\":\"\"}";
        }
        if (userMessage.contains("你是 Agent Team 的 ConflictDetector")) {
            return "{\"hasConflict\":false,\"conflictFields\":[],\"affectedStepIds\":[],\"reason\":\"Mock 未发现冲突。\",\"normalizationAdvice\":\"\"}";
        }
        if (userMessage.contains("You are the GlobalReviewer")) {
            return "{\"action\":\"SUCCESS\",\"reason\":\"Mock 全局终审通过。\",\"questions\":[],\"retryStepIds\":[],\"affectedStepIds\":[],\"revisionInstructions\":\"\",\"stepReflections\":{},\"finalReport\":\"# Mock 最终报告\\\\n\\\\n任务已按 DAG 完成，所有 Step 均已执行并聚合。\"}";
        }
        if (userMessage.contains("你是 Agent Team 的 MergeAgent")) {
            return "# Mock 聚合报告\n\n- 已汇总各 Step 输出。\n- DAG 依赖已完成后再进入聚合。\n- 可作为最终交付草稿。";
        }
        if (userMessage.contains("You are an Executor in an Agent Team.")
            || userMessage.contains("你是 Agent Team 的 Executor")) {
            return "Mock Step 交付物：已完成当前步骤，输出包含目标、执行要点和风险提示。";
        }
        if (userMessage.contains("你是 PlannerArbitration")) {
            return "Mock 仲裁意见：统一采用已完成 Step 的最新口径。";
        }
        return null;
    }

    private String lastJsonString(String text, String fieldName, String fallback) {
        String quotedField = "\"" + fieldName + "\"";
        int fieldIndex = text.lastIndexOf(quotedField);
        if (fieldIndex < 0) {
            return fallback;
        }
        int colon = text.indexOf(':', fieldIndex + quotedField.length());
        if (colon < 0) {
            return fallback;
        }
        int firstQuote = text.indexOf('"', colon + 1);
        if (firstQuote < 0) {
            return fallback;
        }
        int secondQuote = text.indexOf('"', firstQuote + 1);
        if (secondQuote < 0) {
            return fallback;
        }
        String value = text.substring(firstQuote + 1, secondQuote).trim();
        return value.isBlank() ? fallback : value;
    }

    private String jsonEscape(String value) {
        return value == null ? "" : value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"");
    }

    private TokenUsage mockUsage(ProviderRequest request, String response) {
        long promptTokens = roughTokens(request.systemPrompt()) + roughTokens(request.userMessage());
        long completionTokens = roughTokens(response);
        return new TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens);
    }

    private long roughTokens(String text) {
        if (text == null || text.isBlank()) {
            return 1;
        }
        return Math.max(1, (text.length() + 3L) / 4L);
    }
}
