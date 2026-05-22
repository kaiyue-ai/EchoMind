package com.echomind.console.controller.rest;

import com.echomind.console.sensitive.SensitiveDataService;
import com.echomind.console.sensitive.SensitiveDtos.SensitiveEventListResponse;
import com.echomind.console.sensitive.SensitiveDtos.SensitiveRuleListResponse;
import com.echomind.console.sensitive.SensitiveDtos.UpdateSensitiveRulesRequest;
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
@RequestMapping("/api/admin/sensitive")
@RequiredArgsConstructor
public class AdminSensitiveController {

    private final SensitiveDataService sensitiveDataService;

    @GetMapping("/rules")
    public SensitiveRuleListResponse rules() {
        return sensitiveDataService.listRules();
    }

    @PutMapping("/rules")
    public SensitiveRuleListResponse updateRules(@RequestBody UpdateSensitiveRulesRequest request) {
        return sensitiveDataService.updateRules(request);
    }

    @GetMapping("/events")
    public SensitiveEventListResponse events(@RequestParam(required = false) Integer limit) {
        return sensitiveDataService.listEvents(limit);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
