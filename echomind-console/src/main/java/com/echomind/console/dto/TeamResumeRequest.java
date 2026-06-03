package com.echomind.console.dto;

import java.util.Map;

/**
 * 在 Team Run 等待澄清时提交用户补充信息。
 */
public record TeamResumeRequest(
    String clarificationAnswer,
    Map<String, String> stepClarificationAnswers
) {}
