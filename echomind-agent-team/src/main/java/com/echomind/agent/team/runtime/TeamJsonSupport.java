package com.echomind.agent.team.runtime;

import com.echomind.agent.team.model.PlannedStep;
import com.echomind.agent.team.model.ReviewerDecision;
import com.echomind.agent.team.state.ReviewerAction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Team 黑板 JSON 字段和 LLM 结构化响应的转换工具。
 */
@Slf4j
public class TeamJsonSupport {

    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<PlannedStep>> PLANNED_STEPS = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST = new TypeReference<>() {};

    public String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize team JSON: {}", e.getMessage());
            return value instanceof List<?> ? "[]" : "{}";
        }
    }

    public List<String> stringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, STRING_LIST);
        } catch (Exception e) {
            log.warn("Failed to parse string list JSON: {}", e.getMessage());
            return List.of();
        }
    }

    public List<PlannedStep> parsePlan(String raw) {
        if (raw != null && raw.trim().startsWith("[Error]")) {
            throw new IllegalArgumentException("Planner response is an error: " + raw.trim());
        }
        String json = extractJson(raw);
        if (json != null) {
            try {
                JsonNode root = MAPPER.readTree(json);
                JsonNode stepsNode = root.has("steps") ? root.get("steps") : root;
                if (stepsNode.isArray()) {
                    return normalizePlannedSteps(MAPPER.convertValue(stepsNode, MAP_LIST));
                }
            } catch (Exception e) {
                log.warn("Failed to parse planner JSON, falling back to text: {}", e.getMessage());
            }
        }
        return parsePlanText(raw);
    }

    public ReviewerDecision parseDecision(String raw) {
        String json = extractJson(raw);
        if (json == null) {
            throw new IllegalArgumentException("Reviewer response must be valid JSON");
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            ReviewerAction action = parseAction(root.path("action").asText(null));
            List<String> questions = nodeTextList(root.get("questions"));
            List<String> retryStepIds = nodeTextList(root.get("retryStepIds"));
            return new ReviewerDecision(
                action,
                text(root, "reason", ""),
                questions,
                retryStepIds,
                text(root, "revisionInstructions", ""),
                text(root, "finalReport", "")
            );
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Reviewer response JSON is invalid: " + e.getMessage(), e);
        }
    }

    public String extractJson(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) {
                trimmed = trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        if ((trimmed.startsWith("{") && trimmed.endsWith("}"))
            || (trimmed.startsWith("[") && trimmed.endsWith("]"))) {
            return trimmed;
        }
        int objectStart = trimmed.indexOf('{');
        int objectEnd = trimmed.lastIndexOf('}');
        if (objectStart >= 0 && objectEnd > objectStart) {
            return trimmed.substring(objectStart, objectEnd + 1);
        }
        int arrayStart = trimmed.indexOf('[');
        int arrayEnd = trimmed.lastIndexOf(']');
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
            return trimmed.substring(arrayStart, arrayEnd + 1);
        }
        return null;
    }

    private List<PlannedStep> normalizePlannedSteps(List<Map<String, Object>> values) {
        List<PlannedStep> steps = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> value : values) {
            String title = stringValue(value.get("title"));
            String description = stringValue(value.get("description"));
            if (title.isBlank()) {
                title = firstSentence(description);
            }
            if (title.isBlank()) {
                title = "子任务 " + index;
            }
            List<String> capabilities = objectToStringList(value.get("requiredCapabilities"));
            String acceptance = stringValue(value.get("acceptanceCriteria"));
            if (description.isBlank()) {
                description = title;
            }
            steps.add(new PlannedStep(title, description, capabilities, acceptance));
            index++;
        }
        return steps;
    }

    private List<PlannedStep> parsePlanText(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<PlannedStep> steps = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String trimmed = line.trim();
            String cleaned = null;
            if (trimmed.startsWith("- [ ]") || trimmed.startsWith("- [x]")) {
                cleaned = trimmed.replaceFirst("- \\[[ xX]]\\s*", "").trim();
            } else if (trimmed.startsWith("- ")) {
                cleaned = trimmed.substring(2).trim();
            } else if (trimmed.matches("^\\d+[.)]\\s+.*")) {
                cleaned = trimmed.replaceFirst("^\\d+[.)]\\s+", "").trim();
            }
            if (cleaned != null && !cleaned.isBlank()) {
                steps.add(new PlannedStep(cleaned, cleaned, inferCapabilities(cleaned), ""));
            }
        }
        if (steps.isEmpty()) {
            steps.add(new PlannedStep("执行任务", raw.trim(), inferCapabilities(raw), ""));
        }
        return steps;
    }

    private ReviewerAction parseAction(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Reviewer action is required");
        }
        String upper = raw.trim().toUpperCase();
        for (ReviewerAction action : ReviewerAction.values()) {
            if (upper.equals(action.name())) {
                return action;
            }
        }
        throw new IllegalArgumentException("Unknown reviewer action: " + raw);
    }

    private List<String> nodeTextList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(item -> {
                String value = item.asText("");
                if (!value.isBlank()) {
                    values.add(value);
                }
            });
        } else {
            String value = node.asText("");
            if (!value.isBlank()) {
                values.add(value);
            }
        }
        return List.copyOf(values);
    }

    private String text(JsonNode root, String field, String fallback) {
        String value = root.path(field).asText(null);
        return value == null ? (fallback == null ? "" : fallback) : value;
    }

    @SuppressWarnings("unchecked")
    private List<String> objectToStringList(Object value) {
        if (value == null) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                String text = stringValue(item);
                if (!text.isBlank()) {
                    result.add(text.toLowerCase());
                }
            }
        } else {
            String text = stringValue(value);
            for (String part : text.split("[,，、\\s]+")) {
                if (!part.isBlank()) {
                    result.add(part.toLowerCase());
                }
            }
        }
        return List.copyOf(result);
    }

    private List<String> inferCapabilities(String text) {
        String lower = text == null ? "" : text.toLowerCase();
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (lower.contains("weather") || lower.contains("天气") || lower.contains("气温")) {
            tags.add("weather");
        }
        if (lower.contains("search") || lower.contains("查询") || lower.contains("搜索") || lower.contains("场地")) {
            tags.add("search");
        }
        if (lower.contains("计算") || lower.contains("预算") || lower.contains("费用") || lower.contains("math")) {
            tags.add("math");
        }
        if (tags.isEmpty()) {
            tags.add("general");
        }
        return List.copyOf(tags);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String firstSentence(String value) {
        String text = value == null ? "" : value.trim();
        if (text.length() <= 40) {
            return text;
        }
        return text.substring(0, 40);
    }
}
