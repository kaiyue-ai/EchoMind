package com.echomind.console.controller.rest;

import com.echomind.console.usage.UsageDtos.ClientUserListResponse;
import com.echomind.console.usage.UsageDtos.AllCallsResponse;
import com.echomind.console.usage.UsageDtos.UsageSummary;
import com.echomind.console.usage.UsageDtos.UserModelTokenResponse;
import com.echomind.console.usage.UsageDtos.UserCallsResponse;
import com.echomind.console.usage.UsageQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/usage")
@RequiredArgsConstructor
public class AdminUsageController {

    private final UsageQueryService usageQueryService;

    @GetMapping("/summary")
    public UsageSummary summary() {
        return usageQueryService.summary();
    }

    @GetMapping("/users")
    public ClientUserListResponse users() {
        return usageQueryService.users();
    }

    @GetMapping("/calls")
    public AllCallsResponse calls(@RequestParam(required = false) Integer limit) {
        return usageQueryService.allCalls(limit);
    }

    @GetMapping("/user-model-tokens")
    public UserModelTokenResponse userModelTokens() {
        return usageQueryService.userModelTokens();
    }

    @GetMapping("/users/{userId}/calls")
    public UserCallsResponse calls(@PathVariable String userId, @RequestParam(required = false) Integer limit) {
        return usageQueryService.calls(userId, limit);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
