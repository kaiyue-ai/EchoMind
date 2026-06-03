package com.echomind.skill.storage;

import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.ObjectMetadata;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * 阿里云 OSS 对象存储实现。
 *
 * <p>凭证从外部环境注入，不写入配置文件。保存到数据库和聊天历史的是
 * {@code oss://bucket/key}，需要展示或发给模型时再生成签名 URL。</p>
 */
public class AliyunOssObjectStorageService implements ObjectStorageService {

    private static final String OSS_PREFIX = "oss://";

    private final OSS ossClient;
    private final String endpoint;
    private final String bucket;

    public AliyunOssObjectStorageService(String endpoint, String bucket,
                                         String accessKeyId, String accessKeySecret) {
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.ossClient = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
    }

    @Override
    public StoredObject putObject(String key, Path file, String contentType) throws IOException {
        String normalizedKey = normalizeKey(key);
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(Files.size(file));
        if (contentType != null && !contentType.isBlank()) {
            metadata.setContentType(contentType);
        }
        try (var input = Files.newInputStream(file)) {
            ossClient.putObject(bucket, normalizedKey, input, metadata);
        }
        String uri = OSS_PREFIX + bucket + "/" + normalizedKey;
        return new StoredObject(uri, bucket, normalizedKey, urlFor(uri, Duration.ofDays(7)),
            Files.size(file), contentType);
    }

    @Override
    public void deleteObject(String uriOrKey) {
        ossClient.deleteObject(bucket, extractKey(uriOrKey));
    }

    @Override
    public byte[] readObject(String uriOrKey) throws IOException {
        try (var object = ossClient.getObject(bucket, extractKey(uriOrKey));
             var input = object.getObjectContent()) {
            return input.readAllBytes();
        }
    }

    @Override
    public String urlFor(String uriOrKey, Duration ttl) {
        Date expiration = Date.from(Instant.now().plus(ttl == null ? Duration.ofHours(1) : ttl));
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
            bucket, extractKey(uriOrKey), HttpMethod.GET);
        request.setExpiration(expiration);
        URL url = ossClient.generatePresignedUrl(request);
        return url.toString();
    }

    @Override
    public boolean supports(String uriOrKey) {
        return uriOrKey != null && uriOrKey.startsWith(OSS_PREFIX);
    }

    @Override
    public String mode() {
        return "oss";
    }

    private String extractKey(String uriOrKey) {
        if (uriOrKey == null || uriOrKey.isBlank()) {
            throw new IllegalArgumentException("对象 URI 不能为空");
        }
        if (!uriOrKey.startsWith(OSS_PREFIX)) {
            return normalizeKey(uriOrKey);
        }
        String withoutScheme = uriOrKey.substring(OSS_PREFIX.length());
        int separator = withoutScheme.indexOf('/');
        if (separator < 0 || separator == withoutScheme.length() - 1) {
            throw new IllegalArgumentException("非法 OSS URI: " + uriOrKey);
        }
        String uriBucket = withoutScheme.substring(0, separator);
        if (!bucket.equals(uriBucket)) {
            throw new IllegalArgumentException("OSS Bucket 不匹配: " + uriBucket);
        }
        return normalizeKey(withoutScheme.substring(separator + 1));
    }

    private String normalizeKey(String key) {
        String normalized = key == null ? "" : key.replace("\\", "/");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isBlank() || normalized.contains("..")) {
            throw new IllegalArgumentException("非法对象 Key: " + key);
        }
        return normalized;
    }
}
