package com.echomind.agent.team.visualization;

import com.echomind.agent.team.state.TeamRunStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MermaidGeneratorTest {

    @Test
    void generatesEmptyDagWaitingState() {
        String mermaid = MermaidGenerator.generateFromSnapshots(
            "活动策划团队",
            TeamRunStatus.MERGING,
            "COMPLEX",
            List.of(),
            List.of()
        );

        assertThat(mermaid).contains("本次协作流程");
        assertThat(mermaid).contains("团队：活动策划团队");
        assertThat(mermaid).contains("状态：聚合中");
        assertThat(mermaid).contains("暂无 Step");
        assertThat(mermaid).contains("等待 Planner 生成本次 DAG");
        assertThat(mermaid).contains("class EMPTY waiting");
    }
}
