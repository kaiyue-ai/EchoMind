package com.echomind.agent.team.runtime;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.echomind.agent.team.state.TeamRunStatus;
import com.echomind.agent.team.store.TeamRunEntity;
import com.echomind.agent.team.store.TeamRunMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class TeamRunReconcilerTest {

  private final TeamRunMapper runMapper = Mockito.mock(TeamRunMapper.class);
  private final TeamBlackboardService blackboard = Mockito.mock(TeamBlackboardService.class);
  private final TeamRuntimeProperties properties = new TeamRuntimeProperties();
  private final TeamRunReconciler reconciler =
      new TeamRunReconciler(runMapper, blackboard, properties);

  @Test
  void staleNonTerminalRunsAreReconciled() {
    TeamRunEntity run = run("run-1", TeamRunStatus.EXECUTING, Instant.now().minusSeconds(600));
    when(runMapper.selectNonTerminalUpdatedBefore(Mockito.any(), Mockito.eq(20)))
        .thenReturn(List.of(run));
    when(blackboard.reconcileRunFromMysql("run-1", "scheduled")).thenReturn(true);

    reconciler.reconcileStaleRuns();

    verify(blackboard).reconcileRunFromMysql("run-1", "scheduled");
  }

  @Test
  void cutoffUsesGraceWindowBeforeSelectingRuns() {
    when(runMapper.selectNonTerminalUpdatedBefore(Mockito.any(), Mockito.eq(20)))
        .thenReturn(List.of());
    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);

    reconciler.reconcileStaleRuns();

    verify(runMapper).selectNonTerminalUpdatedBefore(cutoff.capture(), Mockito.eq(20));
    long ageSeconds = Instant.now().getEpochSecond() - cutoff.getValue().getEpochSecond();
    org.assertj.core.api.Assertions.assertThat(ageSeconds).isBetween(55L, 185L);
  }

  @Test
  void mapperFailureDoesNotReconcileAnything() {
    when(runMapper.selectNonTerminalUpdatedBefore(Mockito.any(), Mockito.eq(20)))
        .thenThrow(new IllegalStateException("db down"));

    reconciler.reconcileStaleRuns();

    verify(blackboard, never()).reconcileRunFromMysql(Mockito.anyString(), Mockito.anyString());
  }

  private static TeamRunEntity run(String runId, TeamRunStatus status, Instant updatedAt) {
    TeamRunEntity run = new TeamRunEntity();
    run.setRunId(runId);
    run.setStatus(status);
    run.setUpdatedAt(updatedAt);
    return run;
  }
}
