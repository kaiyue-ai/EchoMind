package com.echomind.console.controller;

import com.echomind.common.exception.EchoMindException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.util.Map;

/**
 * 全局异常处理器 —— 将异常转换为结构化 JSON 响应。
 *
 * <p>前端错误拦截器（{@code api/index.js}）读取 {@code err.response?.data?.error}
 * 字段来显示错误信息，因此所有响应都必须包含 {@code error} 字段。
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(EchoMindException.class)
    public ResponseEntity<Map<String, Object>> handleEchoMind(EchoMindException e) {
        log.error("EchoMind exception: {}", e.getMessage(), e);
        return ResponseEntity.internalServerError()
            .body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        log.error("Unhandled exception", e);
        return ResponseEntity.internalServerError()
            .body(Map.of("error", "Internal server error"));
    }
}
