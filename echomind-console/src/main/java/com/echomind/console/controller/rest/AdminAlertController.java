package com.echomind.console.controller.rest;

import com.echomind.console.alerts.AlertDtos.AlertEventListResponse;
import com.echomind.console.alerts.AlertDtos.AlertRuleListResponse;
import com.echomind.console.alerts.AlertDtos.UpdateAlertRulesRequest;
import com.echomind.console.alerts.AlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/alerts")
@RequiredArgsConstructor
public class AdminAlertController {

    private final AlertService alertService;

    @GetMapping("/rules")
    public AlertRuleListResponse rules() {
        return alertService.listRules();
    }

    @PutMapping("/rules")
    public AlertRuleListResponse updateRules(@RequestBody UpdateAlertRulesRequest request) {
        return alertService.updateRules(request);
    }

    @GetMapping("/events")
    public AlertEventListResponse events(@RequestParam(required = false) Integer limit) {
        return alertService.listEvents(limit);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
