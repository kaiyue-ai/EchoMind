package com.echomind.agent.team.runtime;

import com.echomind.agent.Agent;
import com.echomind.agent.team.model.TeamMember;
import com.echomind.agent.team.state.TeamRole;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 根据 Step 所需能力选择最合适的 Executor。
 */
public class TeamCapabilityMatcher {

    public TeamMember selectExecutor(List<TeamMember> members, Collection<String> requiredCapabilities) {
        List<TeamMember> executors = members.stream()
            .filter(member -> member.role() == TeamRole.EXECUTOR)
            .sorted(Comparator.comparingInt(TeamMember::sortOrder))
            .toList();
        if (executors.isEmpty()) {
            return null;
        }
        Set<String> required = normalize(requiredCapabilities);
        if (required.isEmpty()) {
            return executors.get(0);
        }
        return executors.stream()
            .max(Comparator
                .comparingInt((TeamMember member) -> score(member, required))
                .thenComparing(member -> -member.sortOrder()))
            .orElse(executors.get(0));
    }

    private int score(TeamMember member, Set<String> required) {
        Set<String> available = new HashSet<>(normalize(member.capabilityTags()));
        Agent agent = member.agent();
        if (agent != null) {
            available.addAll(normalize(agent.getSkillIds()));
        }
        int score = 0;
        for (String item : required) {
            if (available.contains(item)) {
                score += 3;
                continue;
            }
            for (String capability : available) {
                if (capability.contains(item) || item.contains(capability)) {
                    score += 1;
                    break;
                }
            }
        }
        return score;
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
