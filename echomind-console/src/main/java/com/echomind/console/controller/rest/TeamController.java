package com.echomind.console.controller.rest;

import com.echomind.console.dto.TeamCreateRequest;
import com.echomind.console.dto.TeamExecuteRequest;
import com.echomind.console.dto.TeamResumeRequest;
import com.echomind.console.dto.TeamRunCreateRequest;
import com.echomind.console.dto.TeamRunView;
import com.echomind.console.dto.TeamView;
import com.echomind.console.service.TeamApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Agent Team协作控制器。
 * 管理多Agent团队的创建、任务执行和协作流程可视化入口。
 *
 * <p>团队当前仍是运行时对象，Controller不直接保存团队实例，也不直接调用AgentFactory。
 * 所有团队编排统一放在{@link TeamApplicationService}，便于后续替换成数据库持久化。</p>
 */
@RestController
@RequestMapping("/api/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamApplicationService teamService;

    /**
     * 列出所有活跃的Agent团队。
     */
    @GetMapping
    public ResponseEntity<List<TeamView>> listTeams() {
        return ResponseEntity.ok(teamService.listTeams());
    }

    /**
     * 创建一个新的Agent团队。
     * 需要指定Planner、Executor，可选指定Reviewer角色的Agent ID。
     */
    @PostMapping
    public ResponseEntity<?> createTeam(@RequestBody TeamCreateRequest request) {
        return ResponseEntity.ok(teamService.createTeam(request));
    }

    /**
     * 硬删除团队及其 Run、Step、Event 黑板记录。
     */
    @DeleteMapping("/{teamId}")
    public ResponseEntity<Void> deleteTeam(@PathVariable String teamId) {
        teamService.deleteTeam(teamId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 执行Agent团队任务。
     * 团队协作流程：Planner分解 → Executor执行 → Reviewer评审 → 汇总输出
     */
    @PostMapping("/{teamId}/execute")
    public ResponseEntity<?> executeTask(@PathVariable String teamId,
                                         @RequestBody TeamExecuteRequest request) {
        return ResponseEntity.ok(teamService.executeTask(teamId, request));
    }

    /**
     * 创建一次异步团队协作 Run。
     */
    @PostMapping("/{teamId}/runs")
    public ResponseEntity<TeamRunView> createRun(@PathVariable String teamId,
                                                 @RequestBody TeamRunCreateRequest request) {
        return ResponseEntity.ok(teamService.createRun(teamId, request));
    }

    /**
     * 查询团队最近的协作 Run。
     */
    @GetMapping("/{teamId}/runs")
    public ResponseEntity<List<TeamRunView>> listRuns(@PathVariable String teamId) {
        return ResponseEntity.ok(teamService.listRuns(teamId));
    }

    /**
     * 查询一次 Run 的黑板状态、Step、Event、Mermaid 和最终报告。
     */
    @GetMapping("/{teamId}/runs/{runId}")
    public ResponseEntity<TeamRunView> getRun(@PathVariable String teamId,
                                              @PathVariable String runId) {
        return ResponseEntity.ok(teamService.getRun(teamId, runId));
    }

    /**
     * Reviewer 要求澄清后，用户提交补充信息并继续 Run。
     */
    @PostMapping("/{teamId}/runs/{runId}/resume")
    public ResponseEntity<TeamRunView> resumeRun(@PathVariable String teamId,
                                                 @PathVariable String runId,
                                                 @RequestBody TeamResumeRequest request) {
        return ResponseEntity.ok(teamService.resumeRun(teamId, runId, request));
    }

    /**
     * 获取团队消息总线的待处理消息数。
     */
    @GetMapping("/message-bus/pending")
    public ResponseEntity<Map<String, Object>> getPendingMessages() {
        return ResponseEntity.ok(teamService.pendingMessages());
    }
}
