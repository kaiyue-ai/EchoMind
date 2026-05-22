package com.echomind.console.controller.rest;

import com.echomind.console.dashboard.AdminDashboardDtos.DashboardResponse;
import com.echomind.console.dashboard.AdminDashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final AdminDashboardService dashboardService;

    @GetMapping
    public DashboardResponse dashboard(@RequestParam(defaultValue = "7d") String range) {
        return dashboardService.dashboard(range);
    }
}
