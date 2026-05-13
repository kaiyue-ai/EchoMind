package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.agent.pipeline.PromptBudget;
import com.echomind.agent.tool.Tool;
import com.echomind.agent.tool.ToolFunctionCallback;
import com.echomind.agent.tool.ToolRouter;
import com.echomind.common.model.AgentMessage;
import com.echomind.llm.provider.LlmTool;
import com.echomind.llm.provider.ModelProvider;
import com.echomind.llm.router.DynamicModelRouter;
import com.echomind.llm.router.ModelProviderRegistry;
import com.echomind.llm.router.ModelSpec;
import com.echomind.llm.session.SessionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.function.FunctionCallback;

import java.util.ArrayList;
import java.util.List;

import reactor.core.publisher.Flux;

/**
 * 生成最终回复。
 *
 * <p>这里把当前 Agent 可用的工具注册为 Spring AI 函数回调，
 * 由模型根据工具描述和参数 Schema 自主决定是否调用工具。</p>
 */
@Slf4j
@RequiredArgsConstructor
public class ResultAggregationStage implements PipelineStage {

    private final DynamicModelRouter router;
    private final ModelProviderRegistry providerRegistry;
    private final ToolRouter toolRouter;
    private final PromptBudget promptBudget;

    @Override
    public int order() { return 40; }

    @Override
    public PipelineContext process(PipelineContext ctx) {
        SessionContext sessionCtx = SessionContext.create(ctx.getSessionId());
        if (ctx.getModelId() != null) {
            String[] parts = ctx.getModelId().split(":");
            if (parts.length == 2) {
                sessionCtx = sessionCtx.withModel(parts[0], parts[1]);
            }
        }

        ModelSpec model = router.resolve(sessionCtx);
        ModelProvider provider = providerRegistry.getProvider(model.providerId()).orElse(null);

        if (provider == null) {
            ctx.setFinalResponse("[Error] No model provider available");
            return ctx;
        }

        String message = buildPrompt(ctx);
        try {
            ChatModel chatModel = provider.getChatModel();
            List<Tool> tools = toolsFor(ctx);
            if (!tools.isEmpty() && ctx.getAttachments().isEmpty()) {
                if (supportsProviderNativeTools(provider)) {
                    ctx.setFinalResponse(provider.chatWithTools(model, ctx.getSystemPrompt(), message, llmTools(tools, ctx)));
                } else if (chatModel != null) {
                    ctx.setFinalResponse(callWithFunctions(chatModel, ctx.getSystemPrompt(), message, ctx, tools));
                } else {
                    ctx.setFinalResponse(provider.chat(model, ctx.getSystemPrompt(), message));
                }
            } else {
                ctx.setFinalResponse(provider.chat(model, ctx.getSystemPrompt(), message, ctx.attachmentsForModel()));
            }
        } catch (Exception e) {
            log.error("LLM call failed in aggregation stage", e);
            ctx.setFinalResponse("[Error] LLM call failed: " + e.getMessage());
        }
        return ctx;
    }

    /** 调用已挂载工具回调的模型。 */
    private String callWithFunctions(ChatModel chatModel, String systemPrompt, String message,
                                     PipelineContext ctx, List<Tool> tools) {
        var spec = ChatClient.builder(chatModel).build().prompt().user(message);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            spec = spec.system(systemPrompt);
        }
        List<FunctionCallback> callbacks = buildFunctionCallbacks(tools, ctx);
        if (!callbacks.isEmpty()) {
            spec = spec.functions(callbacks.toArray(new FunctionCallback[0]));
            log.debug("Registered {} function callbacks for LLM call", callbacks.size());
        }

        return spec.call().content();
    }

    /** 流式生成文本片段，供 SSE 接口逐段推送。 */
    public Flux<String> streamResponse(PipelineContext ctx) {
        SessionContext sessionCtx = SessionContext.create(ctx.getSessionId());
        if (ctx.getModelId() != null) {
            String[] parts = ctx.getModelId().split(":");
            if (parts.length == 2) {
                sessionCtx = sessionCtx.withModel(parts[0], parts[1]);
            }
        }

        ModelSpec model = router.resolve(sessionCtx);
        ModelProvider provider = providerRegistry.getProvider(model.providerId()).orElse(null);

        if (provider == null) {
            return Flux.just("[Error] No model provider available");
        }

        String message = buildPrompt(ctx);
        ChatModel chatModel = provider.getChatModel();
        List<Tool> tools = toolsFor(ctx);
        if (!tools.isEmpty() && ctx.getAttachments().isEmpty()) {
            if (supportsProviderNativeTools(provider)) {
                return provider.streamWithTools(model, ctx.getSystemPrompt(), message, llmTools(tools, ctx));
            }
            if (chatModel != null) {
                return streamWithFunctions(chatModel, ctx.getSystemPrompt(), message, ctx, tools);
            }
            return provider.stream(model, ctx.getSystemPrompt(), message);
        } else {
            return provider.stream(model, ctx.getSystemPrompt(), message, ctx.attachmentsForModel());
        }
    }

    private Flux<String> streamWithFunctions(ChatModel chatModel, String systemPrompt, String message,
                                             PipelineContext ctx, List<Tool> tools) {
        var spec = ChatClient.builder(chatModel).build().prompt().user(message);
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            spec = spec.system(systemPrompt);
        }

        List<FunctionCallback> callbacks = buildFunctionCallbacks(tools, ctx);
        if (!callbacks.isEmpty()) {
            spec = spec.functions(callbacks.toArray(new FunctionCallback[0]));
            log.debug("Registered {} function callbacks for LLM stream", callbacks.size());
        }

        return spec.stream().content();
    }

    private List<FunctionCallback> buildFunctionCallbacks(List<Tool> tools, PipelineContext ctx) {
        List<FunctionCallback> callbacks = new ArrayList<>();
        for (Tool tool : tools) {
            callbacks.add(new ToolFunctionCallback(tool, ctx));
        }
        return callbacks;
    }

    private List<LlmTool> llmTools(List<Tool> tools, PipelineContext ctx) {
        return tools.stream()
            .map(tool -> new LlmTool(
                functionName(tool),
                tool.description(),
                tool.parameterSchema(),
                argumentsJson -> new ToolFunctionCallback(tool, ctx).call(argumentsJson)
            ))
            .toList();
    }

    private String functionName(Tool tool) {
        return tool.name().replaceAll("[.\\-]", "_");
    }

    private boolean supportsProviderNativeTools(ModelProvider provider) {
        String simpleName = provider.getClass().getSimpleName();
        return simpleName.equals("OpenAICompatibleProvider") || simpleName.equals("DeepSeekProvider");
    }

    private List<Tool> toolsFor(PipelineContext ctx) {
        Object allowedObj = ctx.getAttributes().get("agentSkillIds");
        List<Tool> allowedTools;
        List<Tool> keywordMatched;
        if (allowedObj instanceof List<?> allowed) {
            allowedTools = toolRouter.listForAgentSkillIds(allowed);
            keywordMatched = toolRouter.matchForAgentSkillIds(ctx.getUserMessage(), allowed);
        } else {
            allowedTools = toolRouter.listForAgentSkillIds(List.of());
            keywordMatched = toolRouter.matchForAgentSkillIds(ctx.getUserMessage(), List.of());
        }
        if (!keywordMatched.isEmpty()) {
            ctx.getAttributes().put("toolMatchMode", "keyword");
            ctx.getAttributes().put("keywordMatchedTools", keywordMatched.stream().map(Tool::name).toList());
            log.debug("Keyword matched {} tool(s): {}", keywordMatched.size(),
                keywordMatched.stream().map(Tool::name).toList());
            return keywordMatched;
        }

        ctx.getAttributes().put("toolMatchMode", "model");
        log.debug("No keyword matched tools, exposing {} allowed tool(s) for model decision", allowedTools.size());
        return allowedTools;
    }

    private String buildPrompt(PipelineContext ctx) {
        List<AgentMessage> allMessages = ctx.getMessages();
        int historySize = Math.max(0, allMessages.size() - 1);
        String historyHeader = "=== Conversation History ===\n";
        String historyFooter = "=== End History ===\n\n";
        String currentUserMessage = truncateTail(safeContent(ctx.getUserMessage()), promptBudget.getMaxChars());
        int historyBudget = promptBudget.getMaxChars() - currentUserMessage.length()
            - historyHeader.length() - historyFooter.length();

        List<String> historyLines = new ArrayList<>();

        if (historySize > 0 && historyBudget > 0) {
            int remaining = historyBudget;
            for (int i = 0; i < historySize && remaining > 0; i++) {
                AgentMessage msg = allMessages.get(i);
                if (!"system".equals(msg.role())) {
                    continue;
                }
                String line = formatHistoryLine(msg, remaining);
                historyLines.add(line);
                remaining -= line.length();
            }

            List<String> recentLines = new ArrayList<>();
            for (int i = historySize - 1; i >= 0 && remaining > 0; i--) {
                AgentMessage msg = allMessages.get(i);
                if ("system".equals(msg.role())) {
                    continue;
                }
                String line = formatHistoryLine(msg, remaining);
                recentLines.add(0, line);
                remaining -= line.length();
            }
            historyLines.addAll(recentLines);
        }

        StringBuilder sb = new StringBuilder();
        if (!historyLines.isEmpty()) {
            sb.append(historyHeader);
            for (String line : historyLines) {
                sb.append(line);
            }
            sb.append(historyFooter);
        }

        sb.append(currentUserMessage);

        return sb.toString();
    }

    private String safeContent(String value) {
        return value == null ? "" : value;
    }

    private String formatHistoryLine(AgentMessage msg, int remaining) {
        String role = safeContent(msg.role());
        String label = switch (role) {
            case "user" -> "User";
            case "assistant" -> "Assistant";
            case "system" -> "System";
            case "tool" -> "Tool";
            default -> role;
        };
        String prefix = label + ": ";
        int messageLimit = "system".equals(role)
            ? promptBudget.getMaxSystemMessageChars()
            : promptBudget.getMaxHistoryMessageChars();
        int contentLimit = Math.max(0, Math.min(messageLimit, remaining - prefix.length() - 1));
        String content = truncateHead(safeContent(msg.content()), contentLimit);
        String line = prefix + content + "\n";
        return line.length() > remaining ? truncateHead(line, remaining) : line;
    }

    private String truncateHead(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        if (maxChars <= 3) {
            return value.substring(value.length() - Math.max(0, maxChars));
        }
        return "..." + value.substring(value.length() - maxChars + 3);
    }

    private String truncateTail(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        if (maxChars <= 3) {
            return value.substring(0, Math.max(0, maxChars));
        }
        return value.substring(0, maxChars - 3) + "...";
    }
}
