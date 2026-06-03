package com.echomind.agent.team.visualization;

import com.echomind.agent.team.runtime.TeamEventSnapshot;
import com.echomind.agent.team.runtime.TeamStepSnapshot;
import com.echomind.agent.team.state.TeamEventType;
import com.echomind.agent.team.state.TeamRiskLevel;
import com.echomind.agent.team.state.TeamRole;
import com.echomind.agent.team.state.TeamRunStatus;
import com.echomind.agent.team.state.TeamStepQualityStatus;
import com.echomind.agent.team.state.TeamStepStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MermaidGeneratorTest {

    @Test
    void generatesCurrentRunDagFromRealSteps() {
        String mermaid = MermaidGenerator.generateFromSnapshots(
            "活动策划团队",
            TeamRunStatus.EXECUTING,
            "COMPLEX",
            List.of(
                step("step-1", 1, "search-venues", "搜索重庆户外场地", List.of(), TeamStepStatus.COMPLETED, "default", 0),
                step("step-2", 2, "check-weather", "检查重庆天气风险", List.of(), TeamStepStatus.RUNNING, "default", 1),
                step("step-3", 3, "merge-plan", "整理可执行活动建议",
                    List.of("step-1", "step-2"), TeamStepStatus.BLOCKED, null, 0)
            ),
            List.of()
        );

        assertThat(mermaid).startsWith("flowchart TD");
        assertThat(mermaid).contains("本次协作流程");
        assertThat(mermaid).contains("#1 搜索重庆户外场地");
        assertThat(mermaid).contains("#2 检查重庆天气风险");
        assertThat(mermaid).contains("#3 整理可执行活动建议");
        assertThat(mermaid).contains("S1 --> S3");
        assertThat(mermaid).contains("S2 --> S3");
        assertThat(mermaid).contains("class S1 completed");
        assertThat(mermaid).contains("class S2 running");
        assertThat(mermaid).contains("class S3 waiting");
        assertThat(mermaid).doesNotContain("TaskPlanner 规划器");
        assertThat(mermaid).doesNotContain("TeamControlCenter 管控中心");
    }

    @Test
    void addsFailureTerminalForFailedRun() {
        String mermaid = MermaidGenerator.generateFromSnapshots(
            "活动策划团队",
            TeamRunStatus.FAILED,
            "COMPLEX",
            List.of(
                step("step-1", 1, "search-venues", "搜索重庆户外场地", List.of(), TeamStepStatus.FAILED, "default", 0),
                step("step-2", 2, "check-weather", "检查重庆天气风险", List.of(), TeamStepStatus.COMPLETED, "default", 0)
            ),
            List.of(event(TeamEventType.RUN_FAILED, null, "Run failed: 存在失败 Step，DAG 停止执行"))
        );

        assertThat(mermaid).contains("FAIL[\"Run 失败<br/>存在失败 Step，DAG 停止执行\"]");
        assertThat(mermaid).contains("S1 --> FAIL");
        assertThat(mermaid).doesNotContain("S2 --> FAIL");
        assertThat(mermaid).contains("class FAIL failed");
    }

    @Test
    void addsCompletedTerminalForCompletedRun() {
        String mermaid = MermaidGenerator.generateFromSnapshots(
            "活动策划团队",
            TeamRunStatus.COMPLETED,
            "SIMPLE",
            List.of(step("step-1", 1, "simple-draft", "整理活动方案", List.of(), TeamStepStatus.COMPLETED, "default", 0)),
            List.of()
        );

        assertThat(mermaid).contains("END[\"Run 完成<br/>最终报告已生成\"]");
        assertThat(mermaid).contains("S1 --> END");
        assertThat(mermaid).contains("class END completed");
    }

    @Test
    void rendersEmptyStateWhenPlannerHasNoStepsYet() {
        String mermaid = MermaidGenerator.generateFromSnapshots(
            "活动策划团队",
            TeamRunStatus.PLANNING,
            "COMPLEX",
            List.of(),
            List.of()
        );

        assertThat(mermaid).contains("EMPTY[\"暂无 Step<br/>等待 Planner 生成本次 DAG\"]");
        assertThat(mermaid).contains("START --> EMPTY");
        assertThat(mermaid).doesNotContain("TaskPlanner 规划器");
    }

    private static TeamStepSnapshot step(String stepId, int index, String clientStepId, String title,
                                         List<String> dependsOn, TeamStepStatus status, String assignedAgentId,
                                         int retryCount) {
        return new TeamStepSnapshot(
            stepId,
            index,
            clientStepId,
            title,
            title + "描述",
            List.of("search"),
            dependsOn,
            TeamRiskLevel.LOW,
            "",
            "输出可交付结果",
            assignedAgentId,
            status,
            "",
            "",
            "",
            TeamStepQualityStatus.PENDING,
            "",
            "",
            "",
            0,
            retryCount,
            null,
            null
        );
    }

    private static TeamEventSnapshot event(TeamEventType type, String stepId, String message) {
        return new TeamEventSnapshot(
            1L,
            "run-1",
            stepId,
            type,
            TeamRole.REVIEWER,
            "default",
            message,
            null,
            Instant.now()
        );
    }
}
