package com.echomind.console.service;

import com.echomind.memory.knowledge.AgentKnowledgeDocument;
import com.echomind.memory.knowledge.AgentKnowledgeManagementPort;
import com.echomind.skill.storage.ObjectStorageService;
import com.echomind.skill.storage.StoredObject;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.List;
import java.util.UUID;

/**
 * Agent 知识库应用服务。
 *
 * <p>REST 层只处理 HTTP 上传和路径参数，知识库文件校验、上传参数整理和
 * Memory 模块调用都收口在这里。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentKnowledgeApplicationService {

    private static final long MAX_KNOWLEDGE_FILE_SIZE = 50 * 1024 * 1024; // 50MB

    private final AgentKnowledgeManagementPort knowledgeService;
    private final ObjectStorageService storageService;

    public List<AgentKnowledgeDocument> listDocuments(String agentId) {
        return knowledgeService.listDocuments(agentId);
    }

    public AgentKnowledgeDocument upload(String agentId, MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("知识库文件不能为空");
        }
        if (file.getSize() > MAX_KNOWLEDGE_FILE_SIZE) {
            throw new IllegalArgumentException("知识库文件大小不能超过 50MB，当前文件大小: "
                + String.format("%.1f", file.getSize() / (1024.0 * 1024.0)) + "MB");
        }
        String originalFilename = file.getOriginalFilename();
        String contentType = contentType(file);
        String extension = extension(originalFilename, contentType);
        String key = "knowledge/" + safeAgentPath(agentId) + "/"
            + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
            + "/" + UUID.randomUUID() + extension;
        Path temp = Files.createTempFile("echomind-knowledge-", extension);
        StoredObject stored = null;
        try {
            file.transferTo(temp.toFile());
            stored = storageService.putObject(key, temp, contentType);
            return knowledgeService.upload(
                agentId,
                originalFilename,
                file.getSize(),
                Files.readAllBytes(temp),
                stored.uri(),
                contentType
            );
        } catch (Exception e) {
            deleteStoredObjectQuietly(stored == null ? null : stored.uri());
            if (e instanceof IOException io) {
                throw io;
            }
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalArgumentException("知识库上传失败: " + e.getMessage(), e);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public void deleteDocument(String agentId, Long documentId) {
        AgentKnowledgeDocument document = findDocument(agentId, documentId);
        knowledgeService.deleteDocument(agentId, documentId);
        deleteStoredObjectQuietly(document.objectUri());
    }

    public void deleteAll(String agentId) {
        List<AgentKnowledgeDocument> documents = knowledgeService.listDocuments(agentId);
        knowledgeService.deleteAll(agentId);
        for (AgentKnowledgeDocument document : documents) {
            deleteStoredObjectQuietly(document.objectUri());
        }
    }

    public KnowledgeDownload download(String agentId, Long documentId) throws IOException {
        AgentKnowledgeDocument document = findDocument(agentId, documentId);
        if (!document.hasOriginalFile() || document.objectUri() == null || document.objectUri().isBlank()) {
            throw new IllegalArgumentException("该知识库文档没有可下载的原文件");
        }
        if (!storageService.supports(document.objectUri())) {
            throw new IllegalArgumentException("该知识库文档的对象存储 URI 不受当前存储服务支持");
        }
        String contentType = document.contentType() == null || document.contentType().isBlank()
            ? MediaType.APPLICATION_OCTET_STREAM_VALUE
            : document.contentType();
        try {
            return new KnowledgeDownload(
                document.fileName(),
                contentType,
                storageService.readObject(document.objectUri())
            );
        } catch (Exception e) {
            log.warn("Failed to read knowledge object uri={} for documentId={}: {}",
                document.objectUri(), documentId, e.getMessage());
            throw new IllegalArgumentException("知识库原文件在对象存储中不存在或已被删除，无法下载");
        }
    }

    private AgentKnowledgeDocument findDocument(String agentId, Long documentId) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId不能为空");
        }
        return knowledgeService.listDocuments(agentId).stream()
            .filter(document -> documentId.equals(document.id()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("知识库文档不存在"));
    }

    private String contentType(MultipartFile file) {
        String contentType = file.getContentType();
        return contentType == null || contentType.isBlank()
            ? MediaType.APPLICATION_OCTET_STREAM_VALUE
            : contentType.toLowerCase(Locale.ROOT);
    }

    private String extension(String filename, String contentType) {
        if (filename != null) {
            int dot = filename.lastIndexOf('.');
            if (dot >= 0 && dot < filename.length() - 1) {
                String ext = filename.substring(dot).toLowerCase(Locale.ROOT);
                if (ext.matches("\\.[a-z0-9]{1,12}")) {
                    return ext;
                }
            }
        }
        return switch (contentType) {
            case MediaType.TEXT_PLAIN_VALUE -> ".txt";
            case MediaType.APPLICATION_PDF_VALUE -> ".pdf";
            default -> ".bin";
        };
    }

    private String safeAgentPath(String agentId) {
        String value = agentId == null ? "unknown" : agentId.trim();
        value = value.replaceAll("[^a-zA-Z0-9._-]", "_");
        return value.isBlank() ? "unknown" : value;
    }

    private void deleteStoredObjectQuietly(String uri) {
        if (uri == null || uri.isBlank() || !storageService.supports(uri)) {
            return;
        }
        try {
            storageService.deleteObject(uri);
        } catch (Exception e) {
            log.warn("Failed to delete knowledge original object uri={}: {}", uri, e.getMessage());
        }
    }

    public record KnowledgeDownload(String fileName, String contentType, byte[] bytes) {
    }
}
