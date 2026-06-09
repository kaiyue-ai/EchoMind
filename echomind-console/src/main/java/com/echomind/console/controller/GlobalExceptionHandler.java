package com.echomind.console.controller;

import com.echomind.common.exception.EchoMindException;
import com.echomind.console.budget.ProviderTokenBudgetExceededException;
import com.echomind.console.quota.TokenQuotaExceededException;
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
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(Map.of(
                "error", "Token 配额已超限",
                "userId", e.userId(),
                "scope", e.scope(),
                "usedTokens", e.usedTokens(),
                "limitTokens", e.limitTokens()
            ));
    }

    @ExceptionHandler(ProviderTokenBudgetExceededException.class)
    public ResponseEntity<Map<String, Object>> handleProviderBudgetExceeded(ProviderTokenBudgetExceededException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .body(Map.of(
                "error", "Provider Token 预算已超限",
                "providerId", e.providerId(),
                "scope", e.scope(),
                "usedTokens", e.usedTokens(),
                "limitTokens", e.limitTokens()
            ));
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
}
