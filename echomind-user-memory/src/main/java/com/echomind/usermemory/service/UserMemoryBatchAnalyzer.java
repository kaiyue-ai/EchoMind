package com.echomind.usermemory.service;

import com.echomind.common.model.AgentMessage;
import com.echomind.llm.provider.ModelProvider;
import com.echomind.llm.provider.ProviderRequest;
import com.echomind.llm.router.DynamicModelRouter;
import com.echomind.llm.router.ModelProviderRegistry;
import com.echomind.llm.router.ModelSpec;
import com.echomind.llm.session.SessionContext;
import com.echomind.memory.usermemory.UserMemoryCategory;
import com.echomind.memory.usermemory.UserMemoryHit;
import com.echomind.usermemory.config.UserMemoryProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 满 5 轮后调用 LLM，一次产出事实层变更和新版用户画像。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserMemoryBatchAnalyzer {

    private static final Pattern JSON_OBJECT_BLOCK = Pattern.compile(
        "```(?:json)?\\s*(\\{.*?\\})\\s*```|(\\{.*\\})",
        Pattern.DOTALL);

    private final DynamicModelRouter router;
    private final ModelProviderRegistry providerRegistry;
    private final ObjectMapper mapper;
    private final UserMemoryProperties properties;

    public UserMemoryBatchResult analyze(String userMemoryKey,
                                         String oldProfile,
                                         List<UserMemoryBatchTurn> turns,
                                         List<UserMemoryHit> relatedFacts) {
        if (turns == null || turns.isEmpty()) {
            return UserMemoryBatchResult.failed(oldProfile);
        }
        try {
            ModelSpec model = resolveModel(userMemoryKey);
            ModelProvider provider = providerRegistry.getProvider(model.providerId()).orElse(null);
            if (provider == null) {
                return UserMemoryBatchResult.failed(oldProfile);
            }
            String response = provider.chat(new ProviderRequest(
                model,
                systemPrompt(),
                userPrompt(oldProfile, turns, relatedFacts),
                List.of(),
                List.of()
            ));
            return parse(response, relatedFacts, oldProfile);
        } catch (Exception e) {
            log.warn("User memory batch analysis failed userMemoryKey={}: {}", userMemoryKey, e.getMessage());
            return UserMemoryBatchResult.failed(oldProfile);
        }
    }

    private ModelSpec resolveModel(String userMemoryKey) {
        String modelId = properties.getExtractorModelId();
        SessionContext ctx = SessionContext.create(userMemoryKey == null ? "user-memory" : userMemoryKey);
        if (modelId != null && modelId.contains(":")) {
            String[] parts = modelId.split(":", 2);
            ctx = ctx.withModel(parts[0], parts[1]);
        }
        return router.resolve(ctx);
    }

    private String systemPrompt() {
        return """
            你是 EchoMind 的用户长期记忆分析器。请基于旧用户画像、最近 5 轮对话和相关旧事实，一次性输出事实层变更和新版用户画像。

            必须遵守：
            - 只输出 JSON 对象，不要 Markdown，不要解释。
            - 用户画像必须在旧用户画像基础上调整，保留仍然有效的旧信息。
            - 只删除被新对话明确否定或覆盖的事实。
            - 不要保存一次性任务、临时链接、搜索结果、模型自己的推测。
            - 不要保存密码、token、AccessKey、Cookie、手机号、身份证等敏感信息。
            - profileSnapshot 使用中文，长度不超过配置限制，内容要稳定、紧凑、可长期复用。
            """;
    }

    private String userPrompt(String oldProfile, List<UserMemoryBatchTurn> turns, List<UserMemoryHit> relatedFacts) {
        return """
            旧用户画像：
            %s

            相关旧事实：
            %s

            最近待处理的 5 轮对话：
            %s

            输出 JSON 格式：
            {
              "factsToAdd": [
                {"content": "用户希望项目注释使用中文", "type": "preference", "evidence": "用户要求注释改成中文", "confidence": 0.9}
              ],
              "factsToUpdate": [
                {"factId": "旧事实 id", "content": "更新后的事实", "type": "preference", "evidence": "新对话证据", "confidence": 0.9}
              ],
              "factsToDelete": [
                {"factId": "旧事实 id", "reason": "用户明确否定"}
              ],
              "profileSnapshot": "基于旧画像调整后的新版用户画像，最多 %d 字"
            }
            """.formatted(
            blankToDefault(oldProfile, "暂无"),
            formatFacts(relatedFacts),
            formatTurns(turns),
            Math.max(1, properties.getProfileMaxChars())
        );
    }

    private UserMemoryBatchResult parse(String response,
                                        List<UserMemoryHit> relatedFacts,
                                        String oldProfile) throws Exception {
        String json = extractJsonObject(response);
        if (json == null) {
            return UserMemoryBatchResult.failed(oldProfile);
        }
        JsonNode root = mapper.readTree(json);
        Set<String> knownFactIds = relatedFacts == null
            ? Set.of()
            : relatedFacts.stream().map(UserMemoryHit::entryId).collect(Collectors.toSet());
        List<UserMemoryBatchResult.FactToAdd> adds = parseAdds(root.path("factsToAdd"));
        List<UserMemoryBatchResult.FactToUpdate> updates = parseUpdates(root.path("factsToUpdate"), knownFactIds);
        List<UserMemoryBatchResult.FactToDelete> deletes = parseDeletes(root.path("factsToDelete"), knownFactIds);
        String profile = text(root.path("profileSnapshot"));
        if (profile == null || profile.isBlank()) {
            profile = oldProfile;
        }
        return new UserMemoryBatchResult(adds, updates, deletes, profile == null ? "" : profile.trim());
    }

    private List<UserMemoryBatchResult.FactToAdd> parseAdds(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<UserMemoryBatchResult.FactToAdd> result = new ArrayList<>();
        for (JsonNode item : node) {
            String content = sanitizeContent(text(item.path("content")), 220);
            if (content == null || isSensitive(content)) {
                continue;
            }
            result.add(new UserMemoryBatchResult.FactToAdd(
                content,
                UserMemoryCategory.from(text(item.path("type"))),
                sanitizeContent(text(item.path("evidence")), 500),
                clamp(item.path("confidence").asDouble(0.6), 0, 1)
            ));
        }
        return result.stream().limit(Math.max(1, properties.getMaxExtractedEntries())).toList();
    }

    private List<UserMemoryBatchResult.FactToUpdate> parseUpdates(JsonNode node, Set<String> knownFactIds) {
        if (!node.isArray()) {
            return List.of();
        }
        List<UserMemoryBatchResult.FactToUpdate> result = new ArrayList<>();
        for (JsonNode item : node) {
            String factId = text(item.path("factId"));
            String content = sanitizeContent(text(item.path("content")), 220);
            if (factId == null || !knownFactIds.contains(factId) || content == null || isSensitive(content)) {
                continue;
            }
            result.add(new UserMemoryBatchResult.FactToUpdate(
                factId,
                content,
                UserMemoryCategory.from(text(item.path("type"))),
                sanitizeContent(text(item.path("evidence")), 500),
                clamp(item.path("confidence").asDouble(0.6), 0, 1)
            ));
        }
        return result.stream().limit(Math.max(1, properties.getMaxExtractedEntries())).toList();
    }

    private List<UserMemoryBatchResult.FactToDelete> parseDeletes(JsonNode node, Set<String> knownFactIds) {
        if (!node.isArray()) {
            return List.of();
        }
        List<UserMemoryBatchResult.FactToDelete> result = new ArrayList<>();
        for (JsonNode item : node) {
            String factId = text(item.path("factId"));
            if (factId != null && knownFactIds.contains(factId)) {
                result.add(new UserMemoryBatchResult.FactToDelete(
                    factId,
                    sanitizeContent(text(item.path("reason")), 300)
                ));
            }
        }
        return result;
    }

    private String extractJsonObject(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        Matcher matcher = JSON_OBJECT_BLOCK.matcher(response);
        while (matcher.find()) {
            String match = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (match != null && !match.isBlank()) {
                return match.trim();
            }
        }
        int start = response.indexOf('{');
        int end = response.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return null;
    }

    private String formatFacts(List<UserMemoryHit> facts) {
        if (facts == null || facts.isEmpty()) {
            return "[]";
        }
        return facts.stream()
            .map(hit -> "- factId=%s [%s] %s (confidence=%.2f)"
                .formatted(hit.entryId(), hit.category().storageValue(), hit.content(), hit.confidence()))
            .collect(Collectors.joining("\n"));
    }

    private String formatTurns(List<UserMemoryBatchTurn> turns) {
        return turns.stream()
            .map(turn -> """
                sessionId=%s agentId=%s time=%s
                %s
                """.formatted(turn.sessionId(), turn.agentId(), turn.createdAt(), formatMessages(turn.messages())))
            .collect(Collectors.joining("\n---\n"));
    }

    private String formatMessages(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        return messages.stream()
            .filter(message -> message != null && message.content() != null && !message.content().isBlank())
            .map(message -> "%s: %s".formatted(message.role(), sanitizeContent(message.content(), 1200)))
            .collect(Collectors.joining("\n"));
    }

    private String sanitizeContent(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() <= maxChars) {
            return trimmed;
        }
        return trimmed.substring(0, Math.max(0, maxChars));
    }

    private boolean isSensitive(String value) {
        if (value == null) {
            return false;
        }
        String lower = value.toLowerCase();
        return lower.contains("accesskey")
            || lower.contains("api key")
            || lower.contains("apikey")
            || lower.contains("token")
            || lower.contains("cookie")
            || lower.contains("password")
            || lower.contains("secret")
            || lower.contains("密码");
    }

    private String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
