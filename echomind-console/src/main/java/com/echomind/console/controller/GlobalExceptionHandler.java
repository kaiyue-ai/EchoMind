package com.echomind.console.controller;

import com.echomind.common.exception.EchoMindException;
import com.echomind.common.exception.ModelInvocationRejectedException;
import com.echomind.common.model.ErrorDetail;
import com.echomind.console.budget.ProviderTokenBudgetExceededException;
import com.echomind.console.quota.TokenQuotaExceededException;
import com.echomind.console.reservation.TokenReservationUnavailableException;
import com.echomind.console.sensitive.SensitiveDataBlockedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * 全局异常处理器 —— 将异常转换为结构化 JSON 响应。
 *
 * <p>前端错误拦截器（{@code api/index.js}）读取 {@code err.response?.data?.error}
 * 字段来显示错误信息，因此所有响应都必须包含 {@code error} 字段。
 */
@ControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(EchoMindException.class)
    public ResponseEntity<Map<String, Object>> handleEchoMind(EchoMindException e) {
        log.error("EchoMind exception: {}", e.getMessage(), e);
        return ResponseEntity.internalServerError()
            .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
            .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getDefaultMessage())
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse("请求参数校验失败");
        return ResponseEntity.badRequest()
            .body(Map.of("error", message));
    }

    @ExceptionHandler(TokenQuotaExceededException.class)
    public ResponseEntity<Map<String, Object>> handleQuotaExceeded(TokenQuotaExceededException e) {
        ErrorDetail detail = userQuotaDetail(e, "INITIAL_RESERVATION");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(Map.of(
                "error", "Token 配额已超限",
                "errorDetail", detail,
                "userId", e.userId(),
                "scope", e.scope(),
                "usedTokens", e.usedTokens(),
                "limitTokens", e.limitTokens()
            ));
    }

    @ExceptionHandler(ProviderTokenBudgetExceededException.class)
    public ResponseEntity<Map<String, Object>> handleProviderBudgetExceeded(ProviderTokenBudgetExceededException e) {
        ErrorDetail detail = providerBudgetDetail("INITIAL_RESERVATION");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(Map.of(
                "error", detail.message(),
                "errorDetail", detail
            ));
    }

    @ExceptionHandler(TokenReservationUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleReservationUnavailable(TokenReservationUnavailableException e) {
        ErrorDetail detail = new ErrorDetail(
            "TOKEN_RESERVATION_UNAVAILABLE",
            "Token 预算预留暂不可用，请稍后重试",
            "INITIAL_RESERVATION",
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of("error", detail.message(), "errorDetail", detail));
    }

    @ExceptionHandler(ModelInvocationRejectedException.class)
    public ResponseEntity<Map<String, Object>> handleModelInvocationRejected(ModelInvocationRejectedException e) {
        ErrorDetail detail = e.errorDetail();
        if (detail == null) {
            detail = new ErrorDetail(null, e.getMessage(), null, null, null, null, null, null, null, null);
        }
        HttpStatus status = "TOKEN_RESERVATION_UNAVAILABLE".equals(detail.code())
            ? HttpStatus.SERVICE_UNAVAILABLE
            : HttpStatus.TOO_MANY_REQUESTS;
        return ResponseEntity.status(status)
            .body(Map.of("error", detail.message(), "errorDetail", detail));
    }

    @ExceptionHandler(SensitiveDataBlockedException.class)
    public ResponseEntity<Map<String, Object>> handleSensitiveBlocked(SensitiveDataBlockedException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(Map.of(
                "error", "请求或响应包含敏感数据，已被阻断",
                "direction", e.direction().name(),
                "ruleName", e.ruleName()
            ));
    }

    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<Map<String, Object>> handleMultipart(MultipartException e) {
        log.warn("Invalid multipart request: {}", e.getMessage());
        return ResponseEntity.badRequest()
            .body(Map.of("error", "文件上传请求格式不正确，请使用 multipart/form-data"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", "Resource not found"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.internalServerError()
            .body(Map.of("error", "Internal server error"));
    }

    private ErrorDetail userQuotaDetail(TokenQuotaExceededException e, String phase) {
        return new ErrorDetail(
            "USER_TOKEN_QUOTA_EXCEEDED",
            "Token 配额已超限",
            phase,
            e.scope(),
            e.limitTokens(),
            e.usedTokens(),
            null,
            null,
            Math.max(0, e.limitTokens() - e.usedTokens()),
            null
        );
    }

    private ErrorDetail providerBudgetDetail(String phase) {
        return new ErrorDetail(
            "PROVIDER_TOKEN_BUDGET_EXCEEDED",
            "模型服务预算不足，请稍后重试或切换模型",
            phase,
            null,
            null,
            null,
            null,
            null,
            null,
            null
        );
    }
}
