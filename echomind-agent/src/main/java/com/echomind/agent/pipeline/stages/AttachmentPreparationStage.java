package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.common.model.MessageAttachment;
import com.echomind.skill.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;

/**
 * 准备模型调用使用的附件。
 *
 * <p>前端展示可以使用站内相对地址，例如 {@code /api/storage/objects/...}；
 * 但云端多模态模型只能读取公网 URL 或 data URL。本阶段只转换“发给模型”的副本，
 * 原始附件仍按对象存储 URI 写入对话记忆，避免把大段 base64 塞进历史记录。</p>
 */
@RequiredArgsConstructor
public class AttachmentPreparationStage implements PipelineStage {

    private final ObjectStorageService storageService;

    @Override
    public int order() {
        return 35;
    }

    @Override
    public PipelineContext process(PipelineContext ctx) {
        if (ctx.getAttachments().isEmpty()) {
            return ctx;
        }

        ctx.getModelAttachments().clear();
        for (MessageAttachment attachment : ctx.getAttachments()) {
            if (attachment == null || !attachment.isImage()) {
                continue;
            }
            ctx.getModelAttachments().add(attachment.withUrl(toModelReadableUrl(attachment)));
        }
        return ctx;
    }

    private String toModelReadableUrl(MessageAttachment attachment) {
        String url = attachment.url();
        if (isHttpUrl(url) || isDataUrl(url)) {
            return url;
        }

        String uri = attachment.uri();
        if (uri != null && storageService.supports(uri) && "oss".equalsIgnoreCase(storageService.mode())) {
            return storageService.urlFor(uri, Duration.ofMinutes(30));
        }
        if (uri != null && storageService.supports(uri)) {
            return toDataUrl(attachment);
        }
        if (url != null && url.startsWith("/api/storage/objects/")) {
            return toDataUrl(attachment);
        }
        throw new IllegalArgumentException("图片附件缺少模型可读取的地址: " + attachment.uri());
    }

    private String toDataUrl(MessageAttachment attachment) {
        try {
            byte[] bytes = storageService.readObject(attachment.uri());
            String mimeType = attachment.mimeType() == null || attachment.mimeType().isBlank()
                ? "image/png"
                : attachment.mimeType();
            return "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取图片附件失败: " + attachment.uri(), e);
        }
    }

    private boolean isHttpUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private boolean isDataUrl(String url) {
        return url != null && url.startsWith("data:");
    }
}
