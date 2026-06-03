package com.echomind.usermemory.service;

import com.echomind.common.model.AgentMessage;
import com.echomind.llm.provider.ModelProvider;
import com.echomind.llm.provider.dto.ProviderRequest;
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

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 轻量模型单轮分析器：提取用户事实、合并相近事实，并按需刷新用户画像。 */
@Service
@Slf4j
@RequiredArgsConstructor
public class UserMemoryAnalyzer {

    private static final Pattern JSON_OBJECT_BLOCK = Pattern.compile(
        "```(?:json)?\\s*(\\{.*?\\})\\s*```|(\\{.*\\})",
        Pattern.DOTALL);

    private final DynamicModelRouter router;
    private final ModelProviderRegistry providerRegistry;
    private final ObjectMapper mapper;
    private final UserMemoryProperties properties;

    public UserMemoryAnalysisResult analyze(String userMemoryKey,
                                            String oldProfile,
                                            UserMemoryTurn turn,
                                            List<UserMemoryHit> relatedFacts,
                                            boolean rememberFacts,
                                            boolean refreshProfile) {
        if (turn == null || turn.messages().isEmpty() || (!rememberFacts && !refreshProfile)) {
            return UserMemoryAnalysisResult.failed(oldProfile);
        }
        try {
            ModelSpec model = resolveModel(userMemoryKey);
            ModelProvider provider = providerRegistry.getProvider(model.providerId()).orElse(null);
            if (provider == null) {
                return UserMemoryAnalysisResult.failed(oldProfile);
            }
            String response = provider.chat(new ProviderRequest(
                model,
                systemPrompt(),
                userPrompt(oldProfile, turn, relatedFacts, rememberFacts, refreshProfile),
                List.of(),
                List.of()
            ));
            return parse(response, relatedFacts, oldProfile, rememberFacts, refreshProfile);
        } catch (Exception e) {
            log.warn("User memory analysis failed userMemoryKey={}: {}", userMemoryKey, e.getMessage());
            return UserMemoryAnalysisResult.failed(oldProfile);
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
            你是 EchoMind 的用户长期记忆轻量分析器。你只处理主模型已经决定需要异步处理的用户事实或用户画像。

            必须遵守：
            - 只输出 JSON 对象，不要 Markdown，不要解释。
            - 不要保存一次性任务、临时链接、搜索结果、工具过程、模型自己的推测。
            - 只依据“本轮用户消息”和相近旧事实处理记忆；助手回复、工具结果、模型总结都不能作为新增、更新或删除用户事实的证据。
            - 不要保存密码、token、AccessKey、Cookie、手机号、身份证等敏感信息。
            - 用户事实必须稳定、可长期复用，并且必须有本轮对话证据。
            - 相近旧事实语义一致时优先 UPDATE，不要重复 ADD；新内容和旧内容冲突时以最新对话为准。
            - 相关旧事实带有 firstObservedAt、lastObservedAt、updatedAt，可用于判断事实新旧和重要性。
            - profileSnapshot 使用中文，在旧用户画像基础上调整，保留仍然有效的旧信息。
            """;
    }

    private String userPrompt(String oldProfile,
                              UserMemoryTurn turn,
                              List<UserMemoryHit> relatedFacts,
                              boolean rememberFacts,
                              boolean refreshProfile) {
        return """
            本轮处理开关：
            rememberFacts=%s
            refreshProfile=%s

            旧用户画像：
            %s

            相近旧事实：
            %s

            本轮用户消息：
            sessionId=%s agentId=%s time=%s
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

            如果 rememberFacts=false，factsToAdd/factsToUpdate/factsToDelete 必须为空数组。
            如果 refreshProfile=false，profileSnapshot 必须原样返回旧用户画像；旧画像为空则返回空字符串。
            """.formatted(
            rememberFacts,
            refreshProfile,
            blankToDefault(oldProfile, "暂无"),
            formatFacts(relatedFacts),
            turn.sessionId(),
            turn.agentId(),
            turn.createdAt(),
            formatMessages(turn.messages()),
            Math.max(1, properties.getProfileMaxChars())
        );
    }

    private UserMemoryAnalysisResult parse(String response,
                                           List<UserMemoryHit> relatedFacts,
                                           String oldProfile,
                                           boolean rememberFacts,
                                           boolean refreshProfile) throws Exception {
        String json = extractJsonObject(response);
        if (json == null) {
            return UserMemoryAnalysisResult.failed(oldProfile);
        }
        JsonNode root = mapper.readTree(json);
        Set<String> knownFactIds = relatedFacts == null
            ? Set.of()
            : relatedFacts.stream().map(UserMemoryHit::entryId).collect(Collectors.toSet());
        List<UserMemoryAnalysisResult.FactToAdd> adds = rememberFacts ? parseAdds(root.path("factsToAdd")) : List.of();
        List<UserMemoryAnalysisResult.FactToUpdate> updates = rememberFacts
            ? parseUpdates(root.path("factsToUpdate"), knownFactIds)
            : List.of();
        List<UserMemoryAnalysisResult.FactToDelete> deletes = rememberFacts
            ? parseDeletes(root.path("factsToDelete"), knownFactIds)
            : List.of();
        String profile = refreshProfile ? text(root.path("profileSnapshot")) : oldProfile;
        if (profile == null || profile.isBlank()) {
            profile = oldProfile;
        }
        return new UserMemoryAnalysisResult(adds, updates, deletes, profile == null ? "" : profile.trim());
    }

    private List<UserMemoryAnalysisResult.FactToAdd> parseAdds(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<UserMemoryAnalysisResult.FactToAdd> result = new ArrayList<>();
        for (JsonNode item : node) {
            String content = sanitizeContent(text(item.path("content")), 220);
            if (content == null || isSensitive(content)) {
                continue;
            }
            result.add(new UserMemoryAnalysisResult.FactToAdd(
                content,
                UserMemoryCategory.from(text(item.path("type"))),
                sanitizeContent(text(item.path("evidence")), 500),
                clamp(item.path("confidence").asDouble(0.6), 0, 1)
            ));
        }
        return result.stream().limit(Math.max(1, properties.getMaxExtractedEntries())).toList();
    }

    private List<UserMemoryAnalysisResult.FactToUpdate> parseUpdates(JsonNode node, Set<String> knownFactIds) {
        if (!node.isArray()) {
            return List.of();
        }
        List<UserMemoryAnalysisResult.FactToUpdate> result = new ArrayList<>();
        for (JsonNode item : node) {
            String factId = text(item.path("factId"));
            String content = sanitizeContent(text(item.path("content")), 220);
            if (factId == null || !knownFactIds.contains(factId) || content == null || isSensitive(content)) {
                continue;
            }
            result.add(new UserMemoryAnalysisResult.FactToUpdate(
                factId,
                content,
                UserMemoryCategory.from(text(item.path("type"))),
                sanitizeContent(text(item.path("evidence")), 500),
                clamp(item.path("confidence").asDouble(0.6), 0, 1)
            ));
        }
        return result.stream().limit(Math.max(1, properties.getMaxExtractedEntries())).toList();
    }

    private List<UserMemoryAnalysisResult.FactToDelete> parseDeletes(JsonNode node, Set<String> knownFactIds) {
        if (!node.isArray()) {
            return List.of();
        }
        List<UserMemoryAnalysisResult.FactToDelete> result = new ArrayList<>();
        for (JsonNode item : node) {
            String factId = text(item.path("factId"));
            if (factId != null && knownFactIds.contains(factId)) {
                result.add(new UserMemoryAnalysisResult.FactToDelete(
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
            .map(hit -> "- factId=%s [%s] %s (confidence=%.2f, score=%.2f, firstObservedAt=%s, lastObservedAt=%s, updatedAt=%s)"
                .formatted(
                    hit.entryId(),
                    hit.category().storageValue(),
                    hit.content(),
                    hit.confidence(),
                    hit.score(),
                    formatTime(hit.firstObservedAt()),
                    formatTime(hit.lastObservedAt()),
                    formatTime(hit.updatedAt())
                ))
            .collect(Collectors.joining("\n"));
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

    private String formatTime(java.time.Instant value) {
        return value == null ? "unknown" : DateTimeFormatter.ISO_INSTANT.format(value);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
