package com.echomind.agent.team.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.echomind.agent.team.config.TeamRedisConfig;
import com.echomind.agent.team.state.TeamStepStatus;
import com.echomind.agent.team.store.TeamStepEntity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

class TeamRedisDagStoreIntegrationTest {

    private LettuceConnectionFactory connectionFactory;
    private TeamRedisDagStore dagStore;
    private String runId;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory("localhost", 6379);
        connectionFactory.afterPropertiesSet();
        try {
            assumeTrue("PONG".equals(connectionFactory.getConnection().ping()),
                "Redis localhost:6379 is not available");
        } catch (RuntimeException e) {
            assumeTrue(false, "Redis localhost:6379 is not available");
        }
        TeamRedisConfig config = new TeamRedisConfig();
        dagStore = new TeamRedisDagStore(
            config.teamStringRedisTemplate(connectionFactory),
            config.teamCompleteStepScript(),
            config.teamClaimSlotScript(),
            config.teamReleaseSlotScript(),
            config.teamMarkReadyScript(),
            config.teamSetControlFlagScript());
        runId = "test-" + UUID.randomUUID();
    }

    @AfterEach
    void tearDown() {
        if (dagStore != null && runId != null) {
            dagStore.destroyDag(runId);
        }
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @Test
    void completeStepCascadesDependentAndIsIdempotent() {
        TeamStepEntity root = step("root", TeamStepStatus.READY, "[]");
        TeamStepEntity child = step("child", TeamStepStatus.BLOCKED, "[\"root\"]");
        dagStore.initializeDag(runId, List.of(root, child), 2);
        assertThat(dagStore.tryClaimSlot(runId)).isEqualTo("root");
        // tryClaimSlot now atomically sets step status to RUNNING
        assertThat(dagStore.getStepStatus(runId, "root")).isEqualTo("RUNNING");

        List<String> firstReady = dagStore.completeStepAndCascade(runId, "root", "output");
        List<String> secondReady = dagStore.completeStepAndCascade(runId, "root", "output-again");

        assertThat(firstReady).containsExactly("child");
        assertThat(secondReady).isEmpty();
        assertThat(dagStore.getStepStatus(runId, "child")).isEqualTo("READY");
        assertThat(dagStore.tryClaimSlot(runId)).isEqualTo("child");
        assertThat(dagStore.getDagField(runId, "completed_count")).isEqualTo("1");
    }

    @Test
    void tryClaimSlotAtomicallySetsStepRunning() {
        TeamStepEntity stepA = step("step-a", TeamStepStatus.READY, "[]");
        TeamStepEntity stepB = step("step-b", TeamStepStatus.READY, "[]");
        dagStore.initializeDag(runId, List.of(stepA, stepB), 3);

        // Before claim: step-a status should be READY
        assertThat(dagStore.getStepStatus(runId, "step-a")).isEqualTo("READY");
        assertThat(dagStore.getDagField(runId, "running_count")).isEqualTo("0");

        // Claim step-a: should atomically set status to RUNNING and increment running_count
        assertThat(dagStore.tryClaimSlot(runId)).isEqualTo("step-a");
        assertThat(dagStore.getStepStatus(runId, "step-a")).isEqualTo("RUNNING");
        assertThat(dagStore.getDagField(runId, "running_count")).isEqualTo("1");

        // Claim step-b: same atomic behavior
        assertThat(dagStore.tryClaimSlot(runId)).isEqualTo("step-b");
        assertThat(dagStore.getStepStatus(runId, "step-b")).isEqualTo("RUNNING");
        assertThat(dagStore.getDagField(runId, "running_count")).isEqualTo("2");

        // No more slots available (max_concurrent=3, but no pending ready steps)
        assertThat(dagStore.tryClaimSlot(runId)).isNull();
    }

    private static TeamStepEntity step(String stepId, TeamStepStatus status, String depsJson) {
        TeamStepEntity step = new TeamStepEntity();
        step.setStepId(stepId);
        step.setRunId("run");
        step.setStatus(status);
        step.setDependsOnStepIdsJson(depsJson);
        return step;
    }
}
