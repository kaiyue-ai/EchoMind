package com.echomind.agent.pipeline.budget;

import com.echomind.agent.pipeline.PipelineContext;

/**
 * Runtime port for platform-level provider token budget checks.
 *
 * <p>The agent runtime only knows when a provider has been resolved. Console
 * supplies the MySQL-backed implementation that decides whether the call is
 * still allowed.</p>
 */
@FunctionalInterface
public interface ProviderTokenBudgetGuard {

    ProviderTokenBudgetGuard NOOP = ctx -> {
    };

    void assertAllowed(PipelineContext ctx);
}
