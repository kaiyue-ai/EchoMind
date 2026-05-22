package com.echomind.agent.team.runtime;

import com.echomind.agent.Agent;
import com.echomind.agent.team.model.TeamMember;
import com.echomind.agent.team.state.TeamRole;
import com.echomind.agent.team.state.TeamStepStatus;
import com.echomind.agent.team.store.TeamStepEntity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamAgentSelectorTest {

    @Test
    void selectsByCapabilityThenLoadAndHealth() {
        TeamAgentSelector selector = new TeamAgentSelector();
        TeamMember search = member("search-agent", List.of("search", "venue"), 10);
        TeamMember general = member("general-agent", List.of("general"), 20);
        TeamStepEntity busy = new TeamStepEntity();
        busy.setAssignedAgentId("search-agent");
        busy.setStatus(TeamStepStatus.RUNNING);

        TeamAgentSelection selection = selector.selectExecutor(
            List.of(search, general),
            List.of("search"),
            List.of(busy)
        );

        assertThat(selection.agentId()).isEqualTo("search-agent");
        assertThat(selection.reason()).contains("能力匹配").contains("健康状态 HEALTHY");
    }

    @Test
    void modelDecisionCanDisambiguateDuplicateAgentIdsByMemberKey() {
        TeamAgentSelector selector = new TeamAgentSelector();
        TeamMember venue = member("default", List.of("search", "venue"), 10);
        TeamMember weather = member("default", List.of("weather"), 20);

        List<TeamAgentSelection> candidates = selector.rankExecutors(
            List.of(venue, weather),
            List.of("weather"),
            List.of()
        );
        TeamAgentSelection selected = selector.matchModelDecision(candidates, "default#20", "default");

        assertThat(selected).isNotNull();
        assertThat(selected.memberKey()).isEqualTo("default#20");
        assertThat(selected.capabilityTags()).containsExactly("weather");
    }

    private TeamMember member(String agentId, List<String> tags, int sortOrder) {
        Agent agent = mock(Agent.class);
        when(agent.getAgentId()).thenReturn(agentId);
        when(agent.getName()).thenReturn(agentId);
        when(agent.getSkillIds()).thenReturn(List.of());
        return new TeamMember(agentId, agentId, TeamRole.EXECUTOR, tags, sortOrder, agent);
    }
}
