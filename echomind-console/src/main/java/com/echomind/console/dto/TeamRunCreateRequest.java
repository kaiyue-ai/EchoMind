package com.echomind.console.dto;

/**
 * 创建异步 Team Run 的请求体。
 */
public record TeamRunCreateRequest(
    String task,
    TeamRunReviewOptionsRequest reviewOptions
) {
    public TeamRunCreateRequest(String task) {
        this(task, null);
    }
}
