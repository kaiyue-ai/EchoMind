package com.echomind.console.dto;

import com.echomind.common.model.MessageAttachment;

/**
 * 文件上传响应。
 *
 * <p>前端聊天只需要 attachment 字段；mode 用于排查当前到底写入 OSS 还是本地兜底。</p>
 */
public record StorageUploadResponse(
    String mode,
    MessageAttachment attachment
) {}
