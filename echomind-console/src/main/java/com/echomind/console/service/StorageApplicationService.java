package com.echomind.console.service;

import com.echomind.common.model.MessageAttachment;
import com.echomind.console.dto.StorageUploadResponse;
import com.echomind.skill.storage.LocalObjectStorageService;
import com.echomind.skill.storage.ObjectStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 对象存储应用服务。
 *
 * <p>负责聊天图片上传校验、对象 Key 生成，以及本地兜底对象的读取代理。
 * 业务层只拿 {@link MessageAttachment}，不会直接处理二进制内容。</p>
 */
@Service
@RequiredArgsConstructor
public class StorageApplicationService {

    private static final long MAX_IMAGE_SIZE = 10 * 1024 * 1024;
    private static final Set<String> SUPPORTED_IMAGE_TYPES = Set.of(
        MediaType.IMAGE_JPEG_VALUE,
        MediaType.IMAGE_PNG_VALUE,
        MediaType.IMAGE_GIF_VALUE,
        "image/webp"
    );

    private final ObjectStorageService storageService;

    /** 上传聊天图片并返回可写入消息历史的附件对象。 */
    public StorageUploadResponse uploadChatImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("图片不能为空");
        }
        String contentType = file.getContentType();
        if (contentType == null || !SUPPORTED_IMAGE_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException("仅支持 JPG、PNG、GIF、WebP 图片");
        }
        if (file.getSize() > MAX_IMAGE_SIZE) {
            throw new IllegalArgumentException("图片不能超过 10MB");
        }

        try {
            String key = "chat-images/" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
                + "/" + UUID.randomUUID() + extension(file.getOriginalFilename(), contentType);
            Path temp = Files.createTempFile("chat-image-", extension(file.getOriginalFilename(), contentType));
            file.transferTo(temp.toFile());
            try {
                var stored = storageService.putObject(key, temp, contentType);
                MessageAttachment attachment = MessageAttachment.image(
                    stored.uri(),
                    stored.url(),
                    contentType,
                    file.getOriginalFilename(),
                    file.getSize()
                );
                return new StorageUploadResponse(storageService.mode(), attachment);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("图片上传失败: " + e.getMessage(), e);
        }
    }

    /** 本地兜底模式下读取对象；OSS 模式的访问由签名 URL 直接处理。 */
    public Resource loadLocalObject(String key) {
        if (!(storageService instanceof LocalObjectStorageService local)) {
            throw new IllegalArgumentException("当前不是本地对象存储模式");
        }
        try {
            Path path = local.resolve(key);
            if (!Files.exists(path) || !Files.isRegularFile(path)) {
                throw new IllegalArgumentException("对象不存在");
            }
            return new UrlResource(path.toUri());
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("对象路径非法", e);
        }
    }

    /** 尽量从文件名推断响应 Content-Type。 */
    public MediaType mediaTypeFor(String key) {
        return MediaTypeFactory.getMediaType(key).orElse(MediaType.APPLICATION_OCTET_STREAM);
    }

    private String extension(String filename, String contentType) {
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0 && dot < filename.length() - 1) {
                String ext = filename.substring(dot).toLowerCase(Locale.ROOT);
                if (ext.matches("\\.[a-z0-9]{1,8}")) {
                    return ext;
                }
            }
        }
        return switch (contentType.toLowerCase(Locale.ROOT)) {
            case MediaType.IMAGE_JPEG_VALUE -> ".jpg";
            case MediaType.IMAGE_PNG_VALUE -> ".png";
            case MediaType.IMAGE_GIF_VALUE -> ".gif";
            case "image/webp" -> ".webp";
            default -> ".bin";
        };
    }
}
