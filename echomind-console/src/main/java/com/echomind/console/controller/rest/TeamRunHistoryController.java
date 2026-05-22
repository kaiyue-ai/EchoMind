package com.echomind.console.controller.rest;

import com.echomind.console.dto.TeamRunView;
import com.echomind.console.service.TeamApplicationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 当前用户的 Team Run 历史入口，与普通聊天会话历史分开查询。
 */
@RestController
@RequestMapping("/api/team-runs")
@RequiredArgsConstructor
public class TeamRunHistoryController {

    private final TeamApplicationService teamService;

    @GetMapping
    public ResponseEntity<List<TeamRunView>> listCurrentUserRuns() {
        return ResponseEntity.ok(teamService.listCurrentUserRuns());
    }
}
