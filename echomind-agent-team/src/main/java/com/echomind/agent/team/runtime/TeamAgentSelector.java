package com.echomind.agent.team.runtime;

import com.echomind.agent.Agent;
import com.echomind.agent.team.model.TeamMember;
import com.echomind.agent.team.state.TeamRole;
import com.echomind.agent.team.state.TeamStepStatus;
import com.echomind.agent.team.store.TeamStepEntity;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Selects Executor by capability, current load, and health status.
 */
public class TeamAgentSelector {

    public TeamAgentSelection selectExecutor(List<TeamMember> members,
                                             Collection<String> requiredCapabilities,
                                             List<TeamStepEntity> runSteps) {
        List<TeamAgentSelection> candidates = rankExecutors(members, requiredCapabilities, runSteps);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    public List<TeamAgentSelection> rankExecutors(List<TeamMember> members,
                                                  Collection<String> requiredCapabilities,
                                                  List<TeamStepEntity> runSteps) {
        List<TeamMember> executors = members.stream()
            .filter(member -> member.role() == TeamRole.EXECUTOR)
            .sorted(Comparator.comparingInt(TeamMember::sortOrder))
            .toList();
        if (executors.isEmpty()) {
            return List.of();
        }
        Set<String> required = normalize(requiredCapabilities);
        Map<String, Long> activeLoads = runSteps == null ? Map.of() : runSteps.stream()
            .filter(step -> step.getAssignedAgentId() != null)
            .filter(step -> step.getStatus() == TeamStepStatus.ASSIGNED
                || step.getStatus() == TeamStepStatus.RUNNING
                || step.getStatus() == TeamStepStatus.RETRYING)
            .collect(Collectors.groupingBy(TeamStepEntity::getAssignedAgentId, Collectors.counting()));

        return executors.stream()
            .map(member -> score(member, required, activeLoads.getOrDefault(member.agentId(), 0L)))
            .sorted(Comparator
                .comparingInt(TeamAgentSelection::totalScore).reversed()
                .thenComparingInt(TeamAgentSelection::sortOrder))
            .toList();
    }

    public TeamAgentSelection matchModelDecision(List<TeamAgentSelection> candidates, String memberKey, String agentId) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        String normalizedMemberKey = safe(memberKey);
        if (!normalizedMemberKey.isBlank()) {
            for (TeamAgentSelection candidate : candidates) {
                if (candidate.memberKey().equalsIgnoreCase(normalizedMemberKey)) {
                    return candidate;
                }
            }
        }
        String normalizedAgentId = safe(agentId);
        if (normalizedAgentId.isBlank()) {
            return null;
        }
        List<TeamAgentSelection> matches = candidates.stream()
            .filter(candidate -> candidate.agentId().equalsIgnoreCase(normalizedAgentId))
            .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private TeamAgentSelection score(TeamMember member, Set<String> required, long activeLoad) {
        int capabilityScore = capabilityScore(member, required);
        int loadScore = Math.max(0, 20 - (int) activeLoad * 5);
        int healthScore = healthScore(healthStatus(member));
        int total = capabilityScore + loadScore + healthScore;
        String reason = "规则评分：能力匹配 " + capabilityScore
            + "，当前负载 " + activeLoad
            + "，健康状态 " + healthStatus(member)
            + "，总分 " + total;
        List<String> tags = new ArrayList<>(member.capabilityTags() == null ? List.of() : member.capabilityTags());
        return new TeamAgentSelection(
            memberKey(member),
            member.agentId(),
            member.agentName(),
            tags,
            member.sortOrder(),
            capabilityScore,
            loadScore,
            healthScore,
            total,
            healthStatus(member),
            "RULE_SCORE",
            reason
        );
    }

    private int capabilityScore(TeamMember member, Set<String> required) {
        if (required == null || required.isEmpty()) {
            return 10;
        }
        Set<String> available = new HashSet<>(normalize(member.capabilityTags()));
        Agent agent = member.agent();
        if (agent != null) {
            available.addAll(normalize(agent.getSkillIds()));
        }
        int score = 0;
        for (String item : required) {
            if (available.contains(item)) {
                score += 10;
                continue;
            }
            for (String capability : available) {
                if (capability.contains(item) || item.contains(capability)) {
                    score += 4;
                    break;
                }
            }
        }
        return score;
    }

    private int healthScore(String healthStatus) {
        return "HEALTHY".equals(healthStatus) ? 10 : 0;
    }

    private String healthStatus(TeamMember member) {
        return "HEALTHY";
    }

    private int sortOrder(List<TeamMember> executors, String agentId) {
        return executors.stream()
            .filter(member -> member.agentId().equals(agentId))
            .findFirst()
            .map(TeamMember::sortOrder)
            .orElse(Integer.MAX_VALUE);
    }

    private String memberKey(TeamMember member) {
        return member.agentId() + "#" + member.sortOrder();
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private Set<String> normalize(Collection<String> values) {
        Set<String> normalized = new HashSet<>();
        if (values == null) {
            return normalized;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isBlank()) {
                normalized.add(trimmed);
            }
        }
        return normalized;
    }
}
