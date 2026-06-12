package com.echomind.agent.team.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

class TeamBlackboardServiceTransactionBoundaryTest {

    @Test
    void longRunningTeamEntrypointsDoNotWrapLlmCallsInTransactions() throws Exception {
        assertNotTransactional("planAndReviewForCoordinator", String.class);
        assertNotTransactional("executeStepPublic", String.class, String.class);
        assertNotTransactional("executeStep", String.class, String.class);
        assertNotTransactional("onDagCompleteInCoordinator", String.class);
    }

    private static void assertNotTransactional(String methodName, Class<?>... parameterTypes)
        throws NoSuchMethodException {
        assertThat(TeamBlackboardService.class.getDeclaredMethod(methodName, parameterTypes)
            .isAnnotationPresent(Transactional.class))
            .as(methodName + " must commit visible state before slow LLM calls")
            .isFalse();
    }
}
