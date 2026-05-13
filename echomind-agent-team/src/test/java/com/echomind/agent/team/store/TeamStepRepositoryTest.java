package com.echomind.agent.team.store;

import com.echomind.agent.team.state.TeamStepStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@TestPropertySource(properties = {
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.sql.init.mode=never"
})
class TeamStepRepositoryTest {

    @Autowired
    private TeamStepRepository repository;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deleteByRunIdOpensTransactionForModifyingQuery() {
        repository.save(step("step-1", "run-1", 1));
        repository.save(step("step-2", "run-1", 2));
        repository.save(step("step-3", "run-2", 1));

        repository.deleteByRunId("run-1");

        assertThat(repository.findByRunIdOrderByStepIndexAsc("run-1")).isEmpty();
        assertThat(repository.findByRunIdOrderByStepIndexAsc("run-2"))
            .extracting(TeamStepEntity::getStepId)
            .containsExactly("step-3");
    }

    private static TeamStepEntity step(String stepId, String runId, int index) {
        TeamStepEntity step = new TeamStepEntity();
        step.setStepId(stepId);
        step.setRunId(runId);
        step.setStepIndex(index);
        step.setTitle("Step " + index);
        step.setStatus(TeamStepStatus.PENDING);
        return step;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = TeamStepEntity.class)
    @EnableJpaRepositories(basePackageClasses = TeamStepRepository.class)
    static class TestApplication {
    }
}
