package com.echomind.agent.team.runtime;

import com.echomind.agent.Agent;
import com.echomind.agent.team.model.TeamMember;
import com.echomind.agent.team.state.TeamRole;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Selects Executor by the capabilities required by the planned Step.
 */
public class TeamAgentSelector {

    public TeamAgentSelection selectExecutor(List<TeamMember> members,
                                             Collection<String> requiredCapabilities) {
        List<TeamAgentSelection> candidates = rankExecutors(members, requiredCapabilities);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    public List<TeamAgentSelection> rankExecutors(List<TeamMember> members,
                                                  Collection<String> requiredCapabilities) {
        List<TeamMember> executors = members.stream()
            .filter(member -> member.role() == TeamRole.EXECUTOR)
            .sorted(Comparator.comparingInt(TeamMember::sortOrder))
            .toList();
        if (executors.isEmpty()) {
            return List.of();
        }
        Set<String> required = normalize(requiredCapabilities);

        return executors.stream()
            .map(member -> score(member, required))
            .sorted(Comparator
                .comparingInt(TeamAgentSelection::matchScore).reversed()
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

    private TeamAgentSelection score(TeamMember member, Set<String> required) {
        int matchScore = matchScore(member, required);
        String reason = "能力匹配分 " + matchScore + "，按 sortOrder " + member.sortOrder() + " 稳定排序";
        List<String> tags = new ArrayList<>(member.capabilityTags() == null ? List.of() : member.capabilityTags());
        return new TeamAgentSelection(
            memberKey(member),
            member.agentId(),
            member.agentName(),
            tags,
            member.sortOrder(),
            matchScore,
            "RULE_SCORE",
            reason
        );
    }

    private int matchScore(TeamMember member, Set<String> required) {
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
