package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.agent.pipeline.budget.ProviderTokenBudgetGuard;
import lombok.RequiredArgsConstructor;

/**
 * Blocks model invocation once a platform provider token budget is exhausted.
 */
@RequiredArgsConstructor
public class ProviderTokenBudgetGuardStage implements PipelineStage {

    private final ProviderTokenBudgetGuard guard;

    @Override
    public int order() {
        return 28;
    }

    @Override
    public PipelineContext process(PipelineContext ctx) {
        ProviderTokenBudgetGuard effective = guard == null ? ProviderTokenBudgetGuard.NOOP : guard;
        try {
            effective.assertAllowed(ctx);
            return ctx;
        } catch (RuntimeException e) {
            ctx.getAttributes().put(PipelineContext.ATTR_MODEL_USAGE_NOT_APPLICABLE, true);
            ctx.getAttributes().put(PipelineContext.ATTR_PROVIDER_TOKEN_BUDGET_BLOCKED, true);
            throw e;
        }
    }
}
