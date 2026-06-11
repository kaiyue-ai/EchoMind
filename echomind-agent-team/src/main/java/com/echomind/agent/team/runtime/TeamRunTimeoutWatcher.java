package com.echomind.agent.team.runtime;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Periodically scans Redis for active DAG runs that have exceeded their timeout.
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnBean(TeamRedisDagStore.class)
@Slf4j
public class TeamRunTimeoutWatcher {

    private final TeamRedisDagStore dagStore;
    private final TeamBlackboardService blackboard;
    private final TeamRuntimeProperties properties;

    public TeamRunTimeoutWatcher(TeamRedisDagStore dagStore,
                                  TeamBlackboardService blackboard,
                                  TeamRuntimeProperties properties) {
        this.dagStore = dagStore;
        this.blackboard = blackboard;
        this.properties = properties;
    }

    @Scheduled(fixedDelay = 15000)
    public void checkRunTimeouts() {
        int timeoutSeconds = properties.getRunTimeoutSeconds();
        if (timeoutSeconds <= 0) {
            return;
        }

        Instant now = Instant.now();
        Set<String> dagKeys;
        try {
            dagKeys = dagStore.scanActiveDagKeys();
        } catch (Exception e) {
            log.warn("Failed to scan active DAG keys for timeout check: {}", e.getMessage());
            return;
        }

        for (String key : dagKeys) {
            try {
                String runId = extractRunId(key);
                if (runId == null) continue;

                String startedAt = dagStore.getDagField(runId, "started_at");
                if (startedAt == null || startedAt.isEmpty()) continue;

                Instant started = Instant.parse(startedAt);
                long elapsed = Duration.between(started, now).getSeconds();
                if (elapsed <= timeoutSeconds) continue;

                String status = dagStore.getDagField(runId, "status");
                if (!"EXECUTING".equals(status) && !"MERGING".equals(status)) {
                    continue;
                }

                log.warn("Run {} timed out after {}s (limit {}s), failing run",
                    runId, elapsed, timeoutSeconds);
                blackboard.failRunFromCoordinator(runId,
                    "Run execution timed out after " + timeoutSeconds + " seconds");
            } catch (Exception e) {
                log.error("Failed to check timeout for DAG key {}", key, e);
            }
        }
    }

    /**
     * Extract runId from a DAG key like "echomind:team:run:{runId}:dag".
     */
    static String extractRunId(String dagKey) {
        String prefix = TeamRedisDagStore.DAG_KEY_PREFIX;
        String suffix = ":dag";
        if (dagKey == null || !dagKey.startsWith(prefix) || !dagKey.endsWith(suffix)) {
            return null;
        }
        int start = prefix.length();
        int end = dagKey.length() - suffix.length();
        if (end <= start) return null;
        return dagKey.substring(start, end);
    }
}
