package com.echomind.usermemory.service;

import com.echomind.memory.usermemory.UserMemoryCategory;

/** LLM 提取出的画像候选项。 */
public record ExtractedUserMemory(
    UserMemoryCategory category,
    String content,
    String evidence,
    double confidence
) {}
