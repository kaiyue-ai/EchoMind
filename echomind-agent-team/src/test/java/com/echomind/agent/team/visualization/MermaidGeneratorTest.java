package com.echomind.agent.team.visualization;

import com.echomind.agent.team.state.TeamRunStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MermaidGeneratorTest {

    @Test
    void generatesFullChineseControlCenterFlow() {
        String mermaid = MermaidGenerator.generateFromSnapshots(
            "活动策划团队",
            TeamRunStatus.MERGING,
            "COMPLEX",
            List.of(),
            List.of()
        );

        assertThat(mermaid).contains("TaskPlanner 规划器");
        assertThat(mermaid).contains("TeamControlCenter 管控中心");
        assertThat(mermaid).contains("AgentSelector 按能力/负载/健康度选择 Agent");
        assertThat(mermaid).contains("冲突检测与统一规范");
        assertThat(mermaid).contains("Planner 仲裁分歧");
        assertThat(mermaid).contains("深度与迭代次数拦截");
        assertThat(mermaid).contains("class M active");
    }
}
