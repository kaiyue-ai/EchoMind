package com.echomind.console.dto;

/**
 * 切换默认模型的请求体。
 */
public record ModelSwitchRequest(
    String providerId,
    String modelName
) {}
