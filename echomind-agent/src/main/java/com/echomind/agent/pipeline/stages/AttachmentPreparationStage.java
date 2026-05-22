package com.echomind.agent.pipeline.stages;

import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.pipeline.PipelineStage;
import com.echomind.common.model.MessageAttachment;
import com.echomind.skill.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;

// 多模态模型能够通过公网的url或者base64编码的图片数据进行对话。
@RequiredArgsConstructor
public class AttachmentPreparationStage implements PipelineStage {

    // 图片附件存储服务
    private final ObjectStorageService storageService;

    @Override
    public int order() {
        return 35;
    }

    @Override
    public PipelineContext process(PipelineContext ctx) {
        // 没有图片附件，则跳过
        if (ctx.getAttachments().isEmpty()) {
            return ctx;
        }

        // 将图片附件转为模型可读取的图片地址
        ctx.getModelAttachments().clear();
        for (MessageAttachment attachment : ctx.getAttachments()) {
            if (attachment == null || !attachment.isImage()) {
                continue;
            }
            // 模型可读取的图片地址
            ctx.getModelAttachments().add(attachment.withUrl(toModelReadableUrl(attachment)));
        }
        // 清除图片附件
        return ctx;
    }

    // 模型可读取的图片地址
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

    // 图片附件转为 base64
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

    // 判断是否是 HTTP URL
    private boolean isHttpUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    // 判断是否是 Data URL
    private boolean isDataUrl(String url) {
        return url != null && url.startsWith("data:");
    }
}
