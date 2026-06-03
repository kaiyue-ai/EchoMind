package com.echomind.agent.team.store;

import com.echomind.common.mybatis.MybatisPlusMetaObjectHandler;
import com.echomind.agent.team.state.TeamStepStatus;
import org.mybatis.spring.annotation.MapperScan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = TeamStepMapperTest.TestApplication.class)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:h2:mem:team-step-mapper-test;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.sql.init.mode=always",
    "spring.sql.init.schema-locations=classpath:team-step-schema.sql",
    "mybatis-plus.configuration.map-underscore-to-camel-case=true"
})
class TeamStepMapperTest {

    @Autowired
    private TeamStepMapper mapper;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void deleteByRunIdOpensTransactionForModifyingQuery() {
        mapper.upsertById(step("step-1", "run-1", 1));
        mapper.upsertById(step("step-2", "run-1", 2));
        mapper.upsertById(step("step-3", "run-2", 1));

        mapper.deleteByRunId("run-1");

        assertThat(mapper.selectByRunIdOrderByStepIndexAsc("run-1")).isEmpty();
        assertThat(mapper.selectByRunIdOrderByStepIndexAsc("run-2"))
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
    @MapperScan(basePackageClasses = TeamStepMapper.class)
    static class TestApplication {
        @org.springframework.context.annotation.Bean
        MybatisPlusMetaObjectHandler mybatisPlusMetaObjectHandler() {
            return new MybatisPlusMetaObjectHandler();
        }
    }
}
