package com.echomind.console.dto;

/**
 * 在 Team Run 等待澄清时提交用户补充信息。
 */
public record TeamResumeRequest(
    String clarificationAnswer
) {}
