package com.echomind.agent.team.runtime;

import com.echomind.agent.team.store.TeamStepEntity;
import com.echomind.agent.team.state.TeamStepStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class TeamRedisDagStore {

    static final String DAG_KEY_PREFIX = "echomind:team:run:";
    static final String STEP_KEY_PREFIX = "echomind:team:run:";
    static final String CONTROL_KEY_PREFIX = "echomind:team:run:";
    static final String PROCESSED_KEY_PREFIX = "echomind:team:msg:";
    static final String RETRY_KEY_PREFIX = "echomind:team:msg-retry:";
    static final Duration DAG_TTL = Duration.ofHours(2);
    static final Duration PROCESSED_TTL = Duration.ofHours(1);

    private final StringRedisTemplate redis;
    private final RedisScript<List> completeStepScript;
    private final RedisScript<String> claimSlotScript;
    private final RedisScript<Long> releaseSlotScript;
    private final RedisScript<Long> releaseSlotForRetryScript;
    private final RedisScript<Long> markReadyScript;
    private final RedisScript<Long> setControlFlagScript;
    private final TeamJsonSupport json = new TeamJsonSupport();

    public TeamRedisDagStore(StringRedisTemplate redis,
                             @Qualifier("teamCompleteStepScript") RedisScript<List> completeStepScript,
                             @Qualifier("teamClaimSlotScript") RedisScript<String> claimSlotScript,
                             @Qualifier("teamReleaseSlotScript") RedisScript<Long> releaseSlotScript,
                             @Qualifier("teamReleaseSlotForRetryScript") RedisScript<Long> releaseSlotForRetryScript,
                             @Qualifier("teamMarkReadyScript") RedisScript<Long> markReadyScript,
                             @Qualifier("teamSetControlFlagScript") RedisScript<Long> setControlFlagScript) {
        this.redis = redis;
        this.completeStepScript = completeStepScript;
        this.claimSlotScript = claimSlotScript;
        this.releaseSlotScript = releaseSlotScript;
        this.releaseSlotForRetryScript = releaseSlotForRetryScript;
        this.markReadyScript = markReadyScript;
        this.setControlFlagScript = setControlFlagScript;
    }

    // ---- Key builders ----

    String dagKey(String runId) {
        return DAG_KEY_PREFIX + runId + ":dag";
    }

    String stepKey(String runId, String stepId) {
        return STEP_KEY_PREFIX + runId + ":step:" + stepId;
    }

    String controlKey(String runId) {
        return CONTROL_KEY_PREFIX + runId + ":control";
    }

    String outputKey(String runId) {
        return DAG_KEY_PREFIX + runId + ":outputs";
    }

    String processedKey(String messageId) {
        return PROCESSED_KEY_PREFIX + messageId;
    }

    String retryKey(String messageId) {
        return RETRY_KEY_PREFIX + messageId;
    }

    // ---- DAG Initialization ----

    public void initializeDag(String runId, List<TeamStepEntity> steps, int maxConcurrent) {
        List<TeamStepEntity> activeSteps = steps == null
            ? List.of()
            : steps.stream()
                .filter(step -> step.getStatus() != TeamStepStatus.SUPERSEDED)
                .toList();
        Map<String, TeamStepStatus> statusByStepId = new HashMap<>();
        for (TeamStepEntity step : activeSteps) {
            statusByStepId.put(step.getStepId(), step.getStatus());
        }

        String dagKey = dagKey(runId);
        Map<String, String> dagFields = new java.util.HashMap<>();
        dagFields.put("total_steps", String.valueOf(activeSteps.size()));
        dagFields.put("completed_count", String.valueOf(activeSteps.stream()
            .filter(step -> step.getStatus() == TeamStepStatus.COMPLETED)
            .count()));
        dagFields.put("running_count", "0");
        dagFields.put("max_concurrent", String.valueOf(maxConcurrent));
        dagFields.put("status", "EXECUTING");
        dagFields.put("pending_ready", "[]");
        dagFields.put("started_at", Instant.now().toString());
        redis.opsForHash().putAll(dagKey, dagFields);
        redis.expire(dagKey, DAG_TTL);

        for (TeamStepEntity step : activeSteps) {
            String stepKey = stepKey(runId, step.getStepId());
            Map<String, String> stepFields = new java.util.HashMap<>();
            stepFields.put("status", redisInitialStatus(step, statusByStepId));
            stepFields.put("deps_json", step.getDependsOnStepIdsJson() != null
                ? step.getDependsOnStepIdsJson() : "[]");
            stepFields.put("dependents_json", "[]");
            stepFields.put("retry_count", String.valueOf(Math.max(0, step.getRetryCount())));
            stepFields.put("output_json", step.getRawOutput() == null ? "" : truncate(step.getRawOutput(), 2000));
            redis.opsForHash().putAll(stepKey, stepFields);
            redis.expire(stepKey, DAG_TTL);
        }

        // Build reverse dependency index
        for (TeamStepEntity step : activeSteps) {
            List<String> deps = parseDepIds(step.getDependsOnStepIdsJson());
            for (String depId : deps) {
                String depKey = stepKey(runId, depId);
                String dependentsJson = (String) redis.opsForHash().get(depKey, "dependents_json");
                List<String> dependents = parseDepIds(dependentsJson);
                if (!dependents.contains(step.getStepId())) {
                    dependents.add(step.getStepId());
                }
                redis.opsForHash().put(depKey, "dependents_json", toJson(dependents));
            }
        }

        // Mark unfinished steps whose dependencies are already complete as READY.
        List<String> readyNow = new ArrayList<>();
        for (TeamStepEntity step : activeSteps) {
            if (!isTerminal(step.getStatus()) && dependenciesCompleted(step, statusByStepId)) {
                String stepKey = stepKey(runId, step.getStepId());
                redis.opsForHash().put(stepKey, "status", "READY");
                readyNow.add(step.getStepId());
            }
        }
        if (!readyNow.isEmpty()) {
            redis.opsForHash().put(dagKey, "pending_ready", toJson(readyNow));
        }
    }

    // ---- Step Completion (Lua script 3) ----

    @SuppressWarnings("unchecked")
    public List<String> completeStepAndCascade(String runId, String stepId, String output) {
        String dagKey = dagKey(runId);
        String stepKey = stepKey(runId, stepId);

        // Find dependents of this step
        String dependentsJson = (String) redis.opsForHash().get(stepKey, "dependents_json");
        List<String> dependents = parseDepIds(dependentsJson);

        // Build keys: dag key + all dependent step keys
        List<String> keys = new ArrayList<>();
        keys.add(dagKey);
        for (String depId : dependents) {
            keys.add(stepKey(runId, depId));
        }

        Object[] args = new Object[]{stepId, truncate(output, 2000), stepKey};
        return (List<String>) redis.execute(completeStepScript, keys, args);
    }

    // ---- Slot Management (Lua scripts 2, 4) ----

    public String tryClaimSlot(String runId) {
        return redis.execute(claimSlotScript,
            Collections.singletonList(dagKey(runId)));
    }

    public void releaseSlot(String runId, String stepId) {
        redis.execute(releaseSlotScript,
            List.of(dagKey(runId), stepKey(runId, stepId)),
            stepId);
    }

    public Long releaseSlotForRetry(String runId, String stepId, int retryCount) {
        return redis.execute(releaseSlotForRetryScript,
            List.of(dagKey(runId), stepKey(runId, stepId)),
            stepId, String.valueOf(Math.max(0, retryCount)));
    }

    // ---- Mark Ready (Lua script 1) ----

    public boolean markStepReady(String runId, String stepId) {
        Long result = redis.execute(markReadyScript,
            List.of(stepKey(runId, stepId), dagKey(runId)),
            stepId);
        return result != null && result == 1L;
    }

    // ---- Control Flags (Lua script 5) ----

    public void setControlFlag(String runId, String flag, String value) {
        redis.execute(setControlFlagScript,
            Collections.singletonList(controlKey(runId)),
            flag, value);
        redis.expire(controlKey(runId), DAG_TTL);
    }

    public String getControlFlag(String runId, String flag) {
        return (String) redis.opsForHash().get(controlKey(runId), flag);
    }

    public void clearControlFlag(String runId, String flag) {
        setControlFlag(runId, flag, "");
    }

    // ---- Step State Queries ----

    public String getStepStatus(String runId, String stepId) {
        return (String) redis.opsForHash().get(stepKey(runId, stepId), "status");
    }

    public int getStepRetryCount(String runId, String stepId) {
        String val = (String) redis.opsForHash().get(stepKey(runId, stepId), "retry_count");
        return val == null ? 0 : Integer.parseInt(val);
    }

    public void setStepRunning(String runId, String stepId) {
        redis.opsForHash().put(stepKey(runId, stepId), "status", "RUNNING");
    }

    public void setStepRetrying(String runId, String stepId, int retryCount) {
        Map<String, String> fields = new java.util.HashMap<>();
        fields.put("status", "RETRYING");
        fields.put("retry_count", String.valueOf(retryCount));
        redis.opsForHash().putAll(stepKey(runId, stepId), fields);
    }

    public String getStepOutput(String runId, String stepId) {
        return (String) redis.opsForHash().get(stepKey(runId, stepId), "output_json");
    }

    // ---- DAG Completion ----

    public boolean isDagComplete(String runId) {
        String dagKey = dagKey(runId);
        String totalStr = (String) redis.opsForHash().get(dagKey, "total_steps");
        String completedStr = (String) redis.opsForHash().get(dagKey, "completed_count");
        String runningStr = (String) redis.opsForHash().get(dagKey, "running_count");
        if (totalStr == null || completedStr == null) return false;
        int total = Integer.parseInt(totalStr);
        int completed = Integer.parseInt(completedStr);
        int running = runningStr == null ? 0 : Integer.parseInt(runningStr);
        return completed >= total && running == 0;
    }

    public boolean hasPendingReady(String runId) {
        String json = (String) redis.opsForHash().get(dagKey(runId), "pending_ready");
        if (json == null || "[]".equals(json)) return false;
        List<String> pending = parseDepIds(json);
        return !pending.isEmpty();
    }

    // ---- DAG Status ----

    public void setDagStatus(String runId, String status) {
        redis.opsForHash().put(dagKey(runId), "status", status);
    }

    // ---- Timeout Support ----

    public String getDagField(String runId, String field) {
        return (String) redis.opsForHash().get(dagKey(runId), field);
    }

    /**
     * Scan for active DAG keys (used by run timeout watcher).
     */
    public java.util.Set<String> scanActiveDagKeys() {
        return redis.keys(DAG_KEY_PREFIX + "*:dag");
    }

    // ---- Cleanup ----

    public void destroyDag(String runId) {
        // Delete dag key and control key directly
        redis.delete(dagKey(runId));
        redis.delete(controlKey(runId));
        // Step keys and output keys are left to TTL expiration
    }

    // ---- Message Deduplication ----

    public boolean isMessageProcessed(String messageId) {
        return Boolean.TRUE.equals(redis.hasKey(processedKey(messageId)));
    }

    public void markMessageProcessed(String messageId) {
        redis.opsForValue().set(processedKey(messageId), "1", PROCESSED_TTL);
        redis.delete(retryKey(messageId));
    }

    public int incrementMessageRetry(String messageId) {
        Long count = redis.opsForValue().increment(retryKey(messageId));
        redis.expire(retryKey(messageId), PROCESSED_TTL);
        return count == null ? 1 : count.intValue();
    }

    // ---- Helpers ----

    @SuppressWarnings("unchecked")
    static List<String> parseDepIds(String raw) {
        if (raw == null || raw.isBlank() || "[]".equals(raw)) {
            return new ArrayList<>();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(raw, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse dependency JSON: {}", raw, e);
            return new ArrayList<>();
        }
    }

    String toJson(List<String> list) {
        return json.toJson(list);
    }

    static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() <= maxLen ? s : s.substring(0, maxLen);
    }

    private static String redisInitialStatus(TeamStepEntity step, Map<String, TeamStepStatus> statusByStepId) {
        TeamStepStatus status = step.getStatus();
        if (status == TeamStepStatus.COMPLETED
            || status == TeamStepStatus.FAILED
            || status == TeamStepStatus.RETRYING) {
            return status.name();
        }
        return dependenciesCompleted(step, statusByStepId) ? "READY" : "BLOCKED";
    }

    private static boolean dependenciesCompleted(TeamStepEntity step, Map<String, TeamStepStatus> statusByStepId) {
        List<String> deps = parseDepIds(step.getDependsOnStepIdsJson());
        if (deps.isEmpty()) {
            return true;
        }
        for (String depId : deps) {
            if (statusByStepId.get(depId) != TeamStepStatus.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTerminal(TeamStepStatus status) {
        return status == TeamStepStatus.COMPLETED
            || status == TeamStepStatus.FAILED
            || status == TeamStepStatus.SUPERSEDED;
    }
}
