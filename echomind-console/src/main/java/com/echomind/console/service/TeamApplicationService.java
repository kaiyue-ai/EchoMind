package com.echomind.console.service;

import com.echomind.agent.team.runtime.TeamBlackboardService;
import com.echomind.agent.team.runtime.TeamMemberSpec;
import com.echomind.agent.team.state.TeamRole;
import com.echomind.agent.team.messaging.TeamMessageBus;
import com.echomind.console.dto.TeamCreateRequest;
import com.echomind.console.dto.TeamMemberRequest;
import com.echomind.console.dto.TeamResumeRequest;
import com.echomind.console.dto.TeamRunCreateRequest;
import com.echomind.console.dto.TeamRunView;
import com.echomind.console.dto.TeamView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Agent Team应用服务。
 *
 * <p>Team定义、Run、Step和Event均落MySQL；内存消息总线只保留运行中通知。</p>
 */
@Service
@RequiredArgsConstructor
public class TeamApplicationService {

    private final TeamBlackboardService blackboardService;
    private final TeamMessageBus messageBus;

    public List<TeamView> listTeams() {
        return blackboardService.listTeams().stream()
            .map(TeamView::from)
            .toList();
    }

    public TeamView createTeam(TeamCreateRequest request) {
        return TeamView.from(blackboardService.createTeam(
            request == null ? null : request.name(),
            toMemberSpecs(request)
        ));
    }

    public void deleteTeam(String teamId) {
        blackboardService.deleteTeam(teamId);
    }

    public TeamRunView createRun(String teamId, TeamRunCreateRequest request) {
        String task = request == null ? null : request.task();
        return TeamRunView.from(blackboardService.createRun(teamId, task));
    }

    public List<TeamRunView> listRuns(String teamId) {
        return blackboardService.listRuns(teamId).stream()
            .map(TeamRunView::from)
            .toList();
    }

    public TeamRunView getRun(String teamId, String runId) {
        return TeamRunView.from(blackboardService.getRun(teamId, runId));
    }

    public TeamRunView resumeRun(String teamId, String runId, TeamResumeRequest request) {
        String answer = request == null ? null : request.clarificationAnswer();
        return TeamRunView.from(blackboardService.resumeRun(teamId, runId, answer));
    }

    public Map<String, Object> pendingMessages() {
        return Map.of("pendingCount", messageBus.pendingCount());
    }

    private List<TeamMemberSpec> toMemberSpecs(TeamCreateRequest request) {
        if (request == null) {
            return List.of();
        }
        if (request.members() == null || request.members().isEmpty()) {
            return List.of();
        }
        return request.members().stream()
            .map(this::toMemberSpec)
            .toList();
    }

    private TeamMemberSpec toMemberSpec(TeamMemberRequest request) {
        return new TeamMemberSpec(
            request.agentId(),
            parseRole(request.role()),
            request.capabilityTags() == null ? List.of() : request.capabilityTags(),
            request.sortOrder() == null ? 0 : request.sortOrder()
        );
    }

    private TeamRole parseRole(String role) {
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("member role is required");
        }
        try {
            return TeamRole.valueOf(role.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown team role: " + role);
        }
    }

}
