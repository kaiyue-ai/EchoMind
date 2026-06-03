package com.echomind.agent.team.runtime;

import com.echomind.agent.team.model.PlannedStep;
import com.echomind.agent.team.model.ReviewerDecision;
import com.echomind.agent.team.model.StepReflection;
import com.echomind.agent.team.state.ReviewerAction;
import com.echomind.agent.team.state.TeamRiskLevel;
import com.echomind.agent.team.state.TeamTaskLevel;
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

    // JSON 转换工具
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    // 字符串列表类型引用
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<PlannedStep>> PLANNED_STEPS = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> OBJECT_MAP = new TypeReference<>() {};

    // 这个方法将对象转换为 JSON 字符串
    public String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize team JSON: {}", e.getMessage());
            return value instanceof List<?> ? "[]" : "{}";
        }
    }

    // 这个方法将 JSON 字符串转换为字符串列表
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

    // 将字符串转为计划步骤列表
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

    // 解析出来任务等级
    public TeamTaskLevel parseTaskLevel(String raw, int stepCount) {
        String json = extractJson(raw); // 先解析出来json
        if (json != null) {
            try {
                JsonNode root = MAPPER.readTree(json);
                String value = root.path("taskLevel").asText("");
                if (!value.isBlank()) {
                    return TeamTaskLevel.valueOf(value.trim().toUpperCase());
                }
            } catch (Exception e) {
                log.warn("Failed to parse task level, falling back to step count: {}", e.getMessage());
            }
        }
        return stepCount <= 1 ? TeamTaskLevel.SIMPLE : TeamTaskLevel.COMPLEX;
    }

    // 解析出来审核决策
    public ReviewerDecision parseDecision(String raw) {
        String json = extractJson(raw); // 先解析出来json
        if (json == null) {
            throw new IllegalArgumentException("Reviewer response must be valid JSON");
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            ReviewerAction action = parseAction(root.path("action").asText(null));
            List<String> questions = nodeTextList(root.get("questions"));
            List<String> retryStepIds = nodeTextList(root.get("retryStepIds"));
            List<String> affectedStepIds = nodeTextList(root.get("affectedStepIds"));
            return new ReviewerDecision(
                action,
                text(root, "reason", ""),
                questions,
                retryStepIds,
                affectedStepIds,
                text(root, "revisionInstructions", ""),
                text(root, "finalReport", ""),
                parseStepReflections(root.get("stepReflections"))
            );
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Reviewer response JSON is invalid: " + e.getMessage(), e);
        }
    }


    public TeamConflictReport parseConflictReport(String raw) {
        String json = extractJson(raw);
        if (json == null) {
            throw new IllegalArgumentException("ConflictDetector response must be valid JSON");
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            return new TeamConflictReport(
                root.path("hasConflict").asBoolean(false),
                nodeTextList(root.get("conflictFields")),
                nodeTextList(root.get("affectedStepIds")),
                text(root, "reason", ""),
                text(root, "normalizationAdvice", "")
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("ConflictDetector response JSON is invalid: " + e.getMessage(), e);
        }
    }

    // 解析出来执行器选择决策
    public TeamExecutorSelectionDecision parseExecutorSelectionDecision(String raw) {
        String json = extractJson(raw); // 先解析出来json
        if (json == null) {
            throw new IllegalArgumentException("AgentSelector response must be valid JSON");
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            return new TeamExecutorSelectionDecision(
                text(root, "memberKey", ""),
                text(root, "agentId", ""),
                text(root, "reason", "")
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("AgentSelector response JSON is invalid: " + e.getMessage(), e);
        }
    }

    // 从文本中提取json
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

    // 这是不完整步骤的规范器,会把不完整的数据不全成规范的PlannedStep对象
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
            String clientStepId = stringValue(value.get("clientStepId"));
            if (clientStepId.isBlank()) {
                clientStepId = stringValue(value.get("id"));
            }
            if (clientStepId.isBlank()) {
                clientStepId = "step-" + index;
            }
            List<String> capabilities = objectToStringList(value.get("requiredCapabilities"));
            String acceptance = stringValue(value.get("acceptanceCriteria"));
            List<String> dependsOn = objectToStringList(value.get("dependsOn"));
            TeamRiskLevel riskLevel = parseRiskLevel(stringValue(value.get("riskLevel")));
            String riskReason = stringValue(value.get("riskReason"));
            if (description.isBlank()) {
                description = title;
            }
            steps.add(new PlannedStep(clientStepId, title, description, capabilities, acceptance,
                dependsOn, riskLevel, riskReason));
            index++;
        }
        return steps;
    }

    // 解析计划文本
    // 这是不完整步骤的规范器,会把不完整的数据不全成规范的PlannedStep对象
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
                steps.add(new PlannedStep("step-" + (steps.size() + 1), cleaned, cleaned,
                    inferCapabilities(cleaned), "", List.of(), TeamRiskLevel.LOW, ""));
            }
        }
        if (steps.isEmpty()) {
            steps.add(new PlannedStep("step-1", "执行任务", raw.trim(), inferCapabilities(raw), "",
                List.of(), TeamRiskLevel.LOW, ""));
        }
        return steps;
    }

    // 解析审核要求的行动
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

    // 从json节点中提取字符串列表
    // 这是不完整步骤的规范器,会把不完整的数据不全成规范的PlannedStep对象
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

    @SuppressWarnings("unchecked")
    // 解析步骤反思映射
    // 这是不完整步骤的规范器,会把不完整的数据不全成规范的PlannedStep对象
    private Map<String, StepReflection> parseStepReflections(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return Map.of();
        }
        Map<String, StepReflection> reflections = new java.util.LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            try {
                Map<String, Object> value = MAPPER.convertValue(entry.getValue(), OBJECT_MAP);
                StepReflection reflection = new StepReflection(
                    objectToStringList(value.get("failedCriteria")),
                    stringValue(value.get("reviewReason")),
                    stringValue(value.get("revisionInstructions")),
                    stringValue(value.get("previousOutputSummary")),
                    objectToStringList(value.get("avoidRepeating"))
                );
                reflections.put(entry.getKey(), reflection);
            } catch (IllegalArgumentException e) {
                log.warn("Failed to parse step reflection for {}: {}", entry.getKey(), e.getMessage());
            }
        });
        return Map.copyOf(reflections);
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
                    result.add(text);
                }
            }
        } else {
            String text = stringValue(value);
            for (String part : text.split("[,，、\\s]+")) {
                if (!part.isBlank()) {
                    result.add(part);
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

    private TeamRiskLevel parseRiskLevel(String raw) {
        if (raw == null || raw.isBlank()) {
            return TeamRiskLevel.LOW;
        }
        try {
            return TeamRiskLevel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return TeamRiskLevel.LOW;
        }
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
