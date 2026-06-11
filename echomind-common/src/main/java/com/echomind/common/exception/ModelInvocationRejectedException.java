package com.echomind.common.exception;

import com.echomind.common.model.ErrorDetail;

/** Thrown when a model invocation is rejected by preflight checks. */
public class ModelInvocationRejectedException extends RuntimeException {
    private final ErrorDetail errorDetail;

    public ModelInvocationRejectedException(String message) {
        super(message);
        this.errorDetail = new ErrorDetail("REJECTED", message);
    }

    public ModelInvocationRejectedException(ErrorDetail errorDetail) {
        super(errorDetail.message());
        this.errorDetail = errorDetail;
    }

    public ErrorDetail errorDetail() {
        return errorDetail;
    }
}
