package com.echomind.common.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 聊天消息附件。
 *
 * <p>目前主要用于图片多模态输入。附件本体不直接塞进记忆或消息队列，
 * 只保存对象存储 URI、临时可访问 URL、MIME 类型和原始文件信息。
 * 这样聊天历史可以长期保存，模型调用时也能拿到可访问的图片地址。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageAttachment(
    /** 附件类型，当前支持 image。 */
    String type,
    /** 对象存储内部 URI，例如 oss://bucket/chat-images/a.png 或 local://...。 */
    String uri,
    /** 给前端展示或模型读取使用的可访问 URL。 */
    String url,
    /** 文件 MIME 类型，例如 image/png。 */
    String mimeType,
    /** 用户上传时的原始文件名。 */
    String fileName,
    /** 文件大小，单位字节。 */
    Long size
) {
    public static final String TYPE_IMAGE = "image";

    /** 创建图片附件。 */
    public static MessageAttachment image(String uri, String url, String mimeType, String fileName, Long size) {
        return new MessageAttachment(TYPE_IMAGE, uri, url, mimeType, fileName, size);
    }

    /** 是否为图片附件。 */
    @JsonIgnore
    public boolean isImage() {
        return TYPE_IMAGE.equalsIgnoreCase(type);
    }

    /** 返回带新 URL 的附件副本。 */
    public MessageAttachment withUrl(String newUrl) {
        return new MessageAttachment(type, uri, newUrl, mimeType, fileName, size);
    }
}
