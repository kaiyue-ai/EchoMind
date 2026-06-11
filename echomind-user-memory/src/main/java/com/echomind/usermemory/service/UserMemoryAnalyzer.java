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

/** 轻量模型：提取会话事件事实，并按需独立刷新用户画像。 */
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

    /** 提取本轮会话中的有价值事件事实。不再产出 profileSnapshot。 */
    public UserMemoryAnalysisResult analyzeEpisodes(String userMemoryKey,
                                                     String oldProfile,
                                                     UserMemoryTurn turn,
                                                     List<UserMemoryHit> relatedFacts) {
        if (turn == null || turn.messages().isEmpty()) {
            return UserMemoryAnalysisResult.failed();
        }
        try {
            ModelSpec model = resolveModel(userMemoryKey);
            ModelProvider provider = providerRegistry.getProvider(model.providerId()).orElse(null);
            if (provider == null) {
                return UserMemoryAnalysisResult.failed();
            }
            String response = provider.chat(new ProviderRequest(
                model,
                episodeSystemPrompt(),
                episodeUserPrompt(oldProfile, turn, relatedFacts),
                List.of(),
                List.of()
            ));
            return parseEpisodeResponse(response, relatedFacts);
        } catch (Exception e) {
            log.warn("User memory episode analysis failed userMemoryKey={}: {}", userMemoryKey, e.getMessage());
            return UserMemoryAnalysisResult.failed();
        }
    }

    /** 独立刷新用户画像：基于 Milvus 中的事实集合生成画像摘要。 */
    public String refreshProfile(String userId, String oldProfile, List<UserMemoryHit> facts) {
        if (facts == null || facts.isEmpty()) {
            return oldProfile == null ? "" : oldProfile;
        }
        try {
            ModelSpec model = resolveModel("user:" + (userId == null ? "default" : userId));
            ModelProvider provider = providerRegistry.getProvider(model.providerId()).orElse(null);
            if (provider == null) {
                return oldProfile == null ? "" : oldProfile;
            }
            String response = provider.chat(new ProviderRequest(
                model,
                profileSystemPrompt(),
                profileUserPrompt(oldProfile, facts),
                List.of(),
                List.of()
            ));
            return cleanProfileResponse(response, oldProfile);
        } catch (Exception e) {
            log.warn("User profile refresh failed userId={}: {}", userId, e.getMessage());
            return oldProfile == null ? "" : oldProfile;
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

    private String episodeSystemPrompt() {
        return """
            你是 EchoMind 的对话事件压缩器。你的任务是从本轮对话中提取有价值的"发生过的事"。

            必须遵守：
            - 只输出 JSON 对象，不要 Markdown，不要解释。
            - 重点识别以下 4 类事实：
              1. EPISODE(事件)：用户完成了一个完整的任务/操作流程、一个完整的问题解决过程
              2. CORRECTION(纠正)：AI 犯了错误或理解偏差，用户明确指出并纠正了
              3. PREFERENCE(偏好)：用户明确表达了偏好、习惯、风格要求或长期约束
              4. KNOWLEDGE(知识)：对话中讨论到的技术事实、架构约束、业务规则
            - 不要保存一次性任务、临时链接、工具查询过程、纯寒暄。
            - 不要保存密码、token、AccessKey、Cookie、手机号、身份证等敏感信息。
            - 每条事实必须带 evidence（本轮对话中的原句证据）和 confidence（0~1 置信度）。
            - 相近旧事实语义一致时优先 UPDATE 而非 ADD；冲突时以最新对话为准。
            - 旧事实带有 firstObservedAt、lastObservedAt、updatedAt，可用于判断新旧和重要性。
            - 只从本轮用户消息中提取证据；助手回复、工具结果不能作为证据。
            """;
    }

    private String episodeUserPrompt(String oldProfile,
                                      UserMemoryTurn turn,
                                      List<UserMemoryHit> relatedFacts) {
        return """
            旧用户画像（上下文参考）：
            %s

            相近旧事实：
            %s

            本轮用户消息：
            sessionId=%s agentId=%s time=%s
            %s

            输出 JSON 格式：
            {
              "factsToAdd": [
                {"content": "用户在上一轮纠正了 LLM 的时区处理错误，正确时区应为 UTC+8", "type": "correction", "evidence": "用户说\\"不对，时区是 UTC+8\\"", "confidence": 0.95}
              ],
              "factsToUpdate": [
                {"factId": "旧事实 id", "content": "更新后的事实", "type": "episode", "evidence": "证据原文", "confidence": 0.9}
              ],
              "factsToDelete": [
                {"factId": "旧事实 id", "reason": "被新对话覆盖或用户明确否定"}
              ]
            }

            注意：
            - type 必须是 episode / correction / preference / knowledge 之一
            - content 最多 220 字，evidence 最多 500 字
            - confidence 范围 0~1
            - factId 必须来自上面"相近旧事实"列表中的 id
            - 旧事实列表为空或没有可合并的旧事实时，factsToUpdate 和 factsToDelete 为空数组
            """.formatted(
            blankToDefault(oldProfile, "暂无"),
            formatFacts(relatedFacts),
            turn.sessionId(),
            turn.agentId(),
            turn.createdAt(),
            formatMessages(turn.messages())
        );
    }

    private String profileSystemPrompt() {
        return """
            你是 EchoMind 的用户画像生成器。根据下方的用户长期事实列表，生成一段中文用户画像摘要。

            要求：
            - 只输出画像文本，不要 JSON、Markdown 或任何格式标记。
            - 归纳用户身份、角色、技术背景、偏好、常见任务类型、重要经历。
            - 保留仍然有效的信息；冲突或过时的事实以更新的为准（参考时间戳）。
            - 输出最多 %d 字。
            """.formatted(Math.max(1, properties.getProfileMaxChars()));
    }

    private String profileUserPrompt(String oldProfile, List<UserMemoryHit> facts) {
        return """
            旧画像：
            %s

            当前事实列表：
            %s

            请基于事实列表生成更新后的用户画像。旧画像中仍然有效的信息应保留。
            """.formatted(
            blankToDefault(oldProfile, "暂无"),
            formatFacts(facts)
        );
    }

    private String cleanProfileResponse(String response, String oldProfile) {
        if (response == null || response.isBlank()) {
            return oldProfile == null ? "" : oldProfile;
        }
        String cleaned = response.trim();
        if (cleaned.length() > properties.getProfileMaxChars()) {
            cleaned = cleaned.substring(0, properties.getProfileMaxChars());
        }
        return cleaned;
    }

    private UserMemoryAnalysisResult parseEpisodeResponse(String response,
                                                           List<UserMemoryHit> relatedFacts) throws Exception {
        String json = extractJsonObject(response);
        if (json == null) {
            return UserMemoryAnalysisResult.failed();
        }
        JsonNode root = mapper.readTree(json);
        Set<String> knownFactIds = relatedFacts == null
            ? Set.of()
            : relatedFacts.stream().map(UserMemoryHit::entryId).collect(Collectors.toSet());
        List<UserMemoryAnalysisResult.FactToAdd> adds = parseAdds(root.path("factsToAdd"));
        List<UserMemoryAnalysisResult.FactToUpdate> updates = parseUpdates(root.path("factsToUpdate"), knownFactIds);
        List<UserMemoryAnalysisResult.FactToDelete> deletes = parseDeletes(root.path("factsToDelete"), knownFactIds);
        return new UserMemoryAnalysisResult(adds, updates, deletes);
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
        // 检测可能泄密的实际凭证值，避免误杀技术术语。例如 token 出现在
        // OAuth token 刷新、JWT token 结构等上下文中是正常技术讨论。
        if (lower.contains("accesskey") || lower.contains("api key") || lower.contains("apikey")
            || lower.contains("cookie") || lower.contains("password") || lower.contains("secret")
            || lower.contains("密码")) {
            return true;
        }
        // token 只在明显是值而非术语时过滤：长度 > 32 或无空格上下文
        return lower.contains("token") && (lower.contains("token=") || lower.contains("token:") || lower.contains("bearer "));
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
