package com.echomind.console.controller.rest;

import com.echomind.agent.Agent;
import com.echomind.agent.AgentFactory;
import com.echomind.agent.team.AgentTeam;
import com.echomind.agent.team.TeamCoordinator;
import com.echomind.agent.team.TeamResult;
import com.echomind.agent.team.messaging.TeamMessageBus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent Team协作控制器。
 * 管理多Agent团队的创建、任务执行和协作流程可视化。
 */
@RestController
@RequestMapping("/api/teams")
public class TeamController {

    private final TeamCoordinator teamCoordinator;
    private final TeamMessageBus messageBus;
    private final AgentFactory agentFactory;

    /** 存储活跃的团队实例 */
    private final Map<String, AgentTeam> activeTeams = new ConcurrentHashMap<>();

    public TeamController(TeamCoordinator teamCoordinator, TeamMessageBus messageBus,
                          AgentFactory agentFactory) {
        this.teamCoordinator = teamCoordinator;
        this.messageBus = messageBus;
        this.agentFactory = agentFactory;
    }

    /**
     * 列出所有活跃的Agent团队。
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listTeams() {
        List<Map<String, Object>> teams = activeTeams.entrySet().stream()
            .map(e -> Map.<String, Object>of(
                "teamId", e.getKey(),
                "name", e.getValue().getName(),
                "roles", e.getValue().getRoles().stream().map(Enum::name).toList()
            ))
            .toList();
        return ResponseEntity.ok(teams);
    }

    /**
     * 创建一个新的Agent团队。
     * 需要指定Planner、Executor、Reviewer三个角色的Agent ID。
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createTeam(@RequestBody Map<String, String> body) {
        String teamName = body.getOrDefault("name", "Team-" + UUID.randomUUID().toString().substring(0, 8));
        String plannerId = body.get("plannerId");
        String executorId = body.get("executorId");
        String reviewerId = body.get("reviewerId");

        Agent planner = agentFactory.get(plannerId != null ? plannerId : "default");
        Agent executor = agentFactory.get(executorId != null ? executorId : "default");
        Agent reviewer = agentFactory.get(reviewerId);

        if (planner == null || executor == null) {
            return ResponseEntity.badRequest()
                .body(Map.of("error", "Planner and Executor agents must exist"));
        }

        String teamId = UUID.randomUUID().toString();
        Map<AgentTeam.TeamRole, Agent> roleMap = new java.util.HashMap<>();
        roleMap.put(AgentTeam.TeamRole.PLANNER, planner);
        roleMap.put(AgentTeam.TeamRole.EXECUTOR, executor);
        if (reviewer != null) {
            roleMap.put(AgentTeam.TeamRole.REVIEWER, reviewer);
        }

        AgentTeam team = new AgentTeam(teamId, teamName, roleMap);
        activeTeams.put(teamId, team);

        return ResponseEntity.ok(Map.of(
            "teamId", teamId,
            "name", teamName,
            "roles", team.getRoles().stream().map(Enum::name).toList()
        ));
    }

    /**
     * 执行Agent团队任务。
     * 团队协作流程：Planner分解 → Executor执行 → Reviewer评审 → 汇总输出
     */
    @PostMapping("/{teamId}/execute")
    public ResponseEntity<Map<String, Object>> executeTask(@PathVariable String teamId,
                                                            @RequestBody Map<String, String> body) {
        AgentTeam team = activeTeams.get(teamId);
        if (team == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Team not found: " + teamId));
        }

        String task = body.get("task");
        if (task == null || task.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "task is required"));
        }

        TeamResult result = teamCoordinator.execute(team, task);
        return ResponseEntity.ok(Map.of(
            "teamId", teamId,
            "status", result.status(),
            "finalOutput", result.finalOutput(),
            "stepResults", result.stepResults(),
            "mermaidDiagram", result.mermaidDiagram()
        ));
    }

    /**
     * 获取团队消息总线的待处理消息数。
     */
    @GetMapping("/message-bus/pending")
    public ResponseEntity<Map<String, Object>> getPendingMessages() {
        return ResponseEntity.ok(Map.of(
            "pendingCount", messageBus.pendingCount()
        ));
    }
}
