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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserProfileExtractor {

    private final DynamicModelRouter router;
    private final ModelProviderRegistry providerRegistry;
    private final ObjectMapper mapper;
    private final UserMemoryProperties properties;

    public List<ExtractedUserMemory> extract(String sessionId,
                                             List<UserMemoryHit> existingProfile,
                                             List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        try {
            ModelSpec model = resolveModel(sessionId);
            ModelProvider provider = providerRegistry.getProvider(model.providerId()).orElse(null);
            if (provider == null) {
                return List.of();
            }
            String response = provider.chat(new ProviderRequest(
                model,
                systemPrompt(),
                userPrompt(existingProfile, messages),
                List.of(),
                List.of()
            ));
            return parse(response).stream()
                .limit(Math.max(1, properties.getMaxExtractedEntries()))
                .toList();
        } catch (Exception e) {
            log.warn("User profile extraction failed sessionId={}: {}", sessionId, e.getMessage());
            return List.of();
        }
    }

    private ModelSpec resolveModel(String sessionId) {
        String modelId = properties.getExtractorModelId();
        SessionContext ctx = SessionContext.create(sessionId == null ? "user-memory" : sessionId);
        if (modelId != null && modelId.contains(":")) {
            String[] parts = modelId.split(":", 2);
            ctx = ctx.withModel(parts[0], parts[1]);
        }
        return router.resolve(ctx);
    }

    private String systemPrompt() {
        return """
            You are a user profile analyst. Based on the conversation, extract or update structured information about the user.
            Output ONLY a JSON array. If nothing new, output [].

            Categories:
            - persona: 性格特征、沟通风格、语气习惯
            - background: 职业、技术栈、经验水平、行业
            - preference: 偏好、习惯、喜欢/不喜欢
            - knowledge: 用户已掌握的知识领域
            - interest: 近期关注/想了解的话题

            Rules:
            - Only new or updated info, max 10 items
            - confidence: 0.0=推测, 1.0=明确陈述
            - content: concise Chinese, under 200 chars
            - evidence: quote or summarize the source message briefly
            """;
    }

    private String userPrompt(List<UserMemoryHit> existingProfile, List<AgentMessage> messages) {
        return """
            Existing profile:
            %s

            Recent conversation:
            %s

            Format:
            [{
              "category": "background",
              "content": "Java后端开发工程师，主要使用Spring Boot",
              "evidence": "用户说：'我们项目用的Spring Boot 3.3'",
              "confidence": 0.9
            }]
            """.formatted(formatExisting(existingProfile), formatMessages(messages));
    }

    private String formatExisting(List<UserMemoryHit> existingProfile) {
        if (existingProfile == null || existingProfile.isEmpty()) {
            return "[]";
        }
        return existingProfile.stream()
            .map(hit -> "- [%s] %s (confidence=%.2f)"
                .formatted(hit.category().storageValue(), hit.content(), hit.confidence()))
            .collect(Collectors.joining("\n"));
    }

    private String formatMessages(List<AgentMessage> messages) {
        return messages.stream()
            .filter(message -> message != null && message.content() != null && !message.content().isBlank())
            .map(message -> "%s: %s".formatted(message.role(), truncate(message.content(), 1200)))
            .collect(Collectors.joining("\n"));
    }

    private List<ExtractedUserMemory> parse(String response) throws Exception {
        String json = extractJsonArray(response);
        if (json == null) {
            return List.of();
        }
        JsonNode root = mapper.readTree(json);
        if (!root.isArray()) {
            return List.of();
        }
        List<ExtractedUserMemory> result = new ArrayList<>();
        for (JsonNode node : root) {
            String content = text(node.path("content"));
            if (content == null || content.isBlank()) {
                continue;
            }
            result.add(new ExtractedUserMemory(
                UserMemoryCategory.from(text(node.path("category"))),
                truncate(content.trim(), 200),
                truncate(blankToDefault(text(node.path("evidence")), ""), 500),
                clamp(node.path("confidence").asDouble(0.5), 0, 1)
            ));
        }
        return result;
    }

    private static final Pattern JSON_ARRAY_BLOCK = Pattern.compile(
        "```(?:json)?\\s*(\\[.*?\\])\\s*```|(\\[.*?\\])",
        Pattern.DOTALL);

    private String extractJsonArray(String response) {
        if (response == null || response.isBlank()) {
            return null;
        }
        Matcher m = JSON_ARRAY_BLOCK.matcher(response);
        while (m.find()) {
            String match = m.group(1) != null ? m.group(1) : m.group(2);
            if (match != null && !match.isBlank()) {
                return match.trim();
            }
        }
        int start = response.indexOf('[');
        int end = response.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return response.substring(start, end + 1);
        }
        return null;
    }

    private String text(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private String blankToDefault(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }

    private String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars));
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
