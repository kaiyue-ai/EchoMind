package com.echomind.agent.team.runtime;

import com.echomind.agent.team.store.TeamRunEntity;
import com.echomind.agent.team.store.TeamRunMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reattaches orphaned non-terminal Team runs to the queue-driven DAG runtime.
 */
@Component
@ConditionalOnBean(TeamRedisDagStore.class)
@Slf4j
public class TeamRunReconciler {

  private static final int MAX_RUNS_PER_SCAN = 20;

  private final TeamRunMapper runMapper;
  private final TeamBlackboardService blackboard;
  private final TeamRuntimeProperties runtimeProperties;

  public TeamRunReconciler(
      TeamRunMapper runMapper,
      TeamBlackboardService blackboard,
      TeamRuntimeProperties runtimeProperties) {
    this.runMapper = runMapper;
    this.blackboard = blackboard;
    this.runtimeProperties =
        runtimeProperties == null ? new TeamRuntimeProperties() : runtimeProperties;
  }

  @Scheduled(initialDelay = 10000, fixedDelay = 15000)
  public void reconcileStaleRuns() {
    Instant cutoff = Instant.now().minus(reconcileGrace());
    List<TeamRunEntity> staleRuns;
    try {
      staleRuns = runMapper.selectNonTerminalUpdatedBefore(cutoff, MAX_RUNS_PER_SCAN);
    } catch (Exception e) {
      log.warn("Failed to load stale Team runs for reconciliation: {}", e.getMessage());
      return;
    }
    for (TeamRunEntity run : staleRuns) {
      try {
        boolean reconciled = blackboard.reconcileRunFromMysql(run.getRunId(), "scheduled");
        if (reconciled) {
          log.warn(
              "Reconciled stale Team run runId={} status={} updatedAt={}",
              run.getRunId(),
              run.getStatus(),
              run.getUpdatedAt());
        }
      } catch (Exception e) {
        log.error("Failed to reconcile stale Team run {}", run.getRunId(), e);
      }
    }
  }

  Duration reconcileGrace() {
    int stepTimeout = runtimeProperties.getStepTimeoutSeconds();
    int seconds = Math.max(60, Math.min(stepTimeout, 180));
    return Duration.ofSeconds(seconds);
  }
}
