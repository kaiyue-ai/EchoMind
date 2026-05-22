package com.echomind.console.controller.rest;

import com.echomind.console.quota.TokenQuotaDtos.TokenQuotaListResponse;
import com.echomind.console.quota.TokenQuotaDtos.TokenQuotaView;
import com.echomind.console.quota.TokenQuotaDtos.UpdateTokenQuotaRequest;
import com.echomind.console.quota.TokenQuotaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/quotas")
@RequiredArgsConstructor
public class AdminQuotaController {

    private final TokenQuotaService quotaService;

    @GetMapping
    public TokenQuotaListResponse list() {
        return quotaService.list();
    }

    @PutMapping("/users/{userId}")
    public TokenQuotaView update(@PathVariable String userId, @RequestBody UpdateTokenQuotaRequest request) {
        return quotaService.update(userId, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
