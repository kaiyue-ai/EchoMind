package com.echomind.agent.pipeline;

import com.echomind.common.exception.ModelInvocationRejectedException;
import com.echomind.llm.provider.dto.ProviderRequest;

/** Preflight check result before model invocation. */
public interface ModelInvocationPreflight {

    ModelInvocationPreflight NOOP = (ctx, request) -> {};

    void beforeInvoke(PipelineContext ctx, ProviderRequest request) throws ModelInvocationRejectedException;
}
