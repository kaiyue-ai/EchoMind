package com.echomind.skill.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * 本地对象存储兜底实现。
 *
 * <p>当 OSS 环境变量没有配置完整时使用它，保证开发和测试环境不被外部云服务阻塞。
 * 返回的 URL 走后端 {@code /api/storage/objects/**} 代理读取。</p>
 */
public class LocalObjectStorageService implements ObjectStorageService {

    private final Path rootDir;

    public LocalObjectStorageService(Path rootDir) {
        this.rootDir = rootDir;
        try {
            Files.createDirectories(rootDir);
        } catch (IOException e) {
            throw new IllegalStateException("无法创建本地对象存储目录: " + rootDir, e);
        }
    }

    @Override
    public StoredObject putObject(String key, Path file, String contentType) throws IOException {
        String normalizedKey = normalizeKey(key);
        Path target = resolveKey(normalizedKey);
        Files.createDirectories(target.getParent());
        Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        long size = Files.size(target);
        return new StoredObject(
            "local://" + normalizedKey,
            "local",
            normalizedKey,
            "/api/storage/objects/" + normalizedKey.replace("\\", "/"),
            size,
            contentType
        );
    }

    @Override
    public void deleteObject(String uriOrKey) throws IOException {
        Files.deleteIfExists(resolveKey(extractKey(uriOrKey)));
    }

    @Override
    public byte[] readObject(String uriOrKey) throws IOException {
        return Files.readAllBytes(resolveKey(extractKey(uriOrKey)));
    }

    @Override
    public String urlFor(String uriOrKey, Duration ttl) {
        return "/api/storage/objects/" + extractKey(uriOrKey).replace("\\", "/");
    }

    @Override
    public boolean supports(String uriOrKey) {
        return uriOrKey != null && uriOrKey.startsWith("local://");
    }

    @Override
    public String mode() {
        return "local";
    }

    /** 把 local:// URI 或普通 key 解析为本地文件路径。 */
    public Path resolve(String uriOrKey) {
        return resolveKey(extractKey(uriOrKey));
    }

    private Path resolveKey(String key) {
        Path resolved = rootDir.resolve(normalizeKey(key)).normalize();
        if (!resolved.startsWith(rootDir.normalize())) {
            throw new IllegalArgumentException("对象 Key 越界: " + key);
        }
        return resolved;
    }

    private String extractKey(String uriOrKey) {
        if (uriOrKey == null || uriOrKey.isBlank()) {
            throw new IllegalArgumentException("对象 URI 不能为空");
        }
        if (uriOrKey.startsWith("local://")) {
            return normalizeKey(uriOrKey.substring("local://".length()));
        }
        return normalizeKey(uriOrKey);
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
