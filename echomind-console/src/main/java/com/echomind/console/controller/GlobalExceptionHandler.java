package com.echomind.console.controller;

import com.echomind.common.exception.EchoMindException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
