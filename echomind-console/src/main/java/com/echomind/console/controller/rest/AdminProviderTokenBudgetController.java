package com.echomind.console.controller.rest;

import com.echomind.console.budget.ProviderTokenBudgetDtos.ProviderTokenBudgetListResponse;
import com.echomind.console.budget.ProviderTokenBudgetDtos.UpdateProviderTokenBudgetsRequest;
import com.echomind.console.budget.ProviderTokenBudgetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/provider-token-budgets")
@RequiredArgsConstructor
public class AdminProviderTokenBudgetController {

    private final ProviderTokenBudgetService budgetService;

    @GetMapping
    public ProviderTokenBudgetListResponse list() {
        return budgetService.list();
    }

    @PutMapping
    public ProviderTokenBudgetListResponse update(@RequestBody UpdateProviderTokenBudgetsRequest request) {
        return budgetService.update(request);
    }
}
