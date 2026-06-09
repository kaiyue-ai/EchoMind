package com.echomind.boot.autoconfigure;

import com.echomind.boot.properties.EchoMindProperties;
import com.echomind.memory.knowledge.AgentKnowledgeDocument;
import com.echomind.memory.knowledge.AgentKnowledgeManagementPort;
import com.echomind.skill.storage.ObjectStorageService;
import com.echomind.skill.storage.StoredObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Seeds private knowledge documents for configured default Agents.
 */
@Slf4j
final class AgentKnowledgeBootstrapper {

    private final EchoMindProperties.AgentBootstrap config;
    private final AgentKnowledgeManagementPort knowledgeService;
    private final ObjectStorageService storageService;
    private final ResourceLoader resourceLoader;

    AgentKnowledgeBootstrapper(EchoMindProperties.AgentBootstrap config,
                               AgentKnowledgeManagementPort knowledgeService,
                               ObjectStorageService storageService,
                               ResourceLoader resourceLoader) {
        this.config = config == null ? new EchoMindProperties.AgentBootstrap() : config;
        this.knowledgeService = knowledgeService;
        this.storageService = storageService;
        this.resourceLoader = resourceLoader;
    }

    int seedAll() {
        int seeded = 0;
        for (EchoMindProperties.KnowledgeSeed seed : seeds()) {
            if (seedOne(seed)) {
                seeded++;
            }
        }
        return seeded;
    }

    private boolean seedOne(EchoMindProperties.KnowledgeSeed seed) {
        if (seed == null || !seed.isEnabled()) {
            return false;
        }
        String agentId = trim(seed.getAgentId());
        if (agentId.isBlank()) {
            log.warn("Skipping agent knowledge seed with empty agentId");
            return false;
        }
        String fileName = safeFileName(seed.getFileName());
        try {
            if (hasDocument(agentId, fileName)) {
                log.info("Agent knowledge seed already exists agentId={} fileName={}", agentId, fileName);
                return false;
            }
            byte[] bytes = seedBytes(seed);
            String contentType = contentType(seed.getContentType());
            StoredObject stored = putSeedObject(agentId, fileName, bytes, contentType);
            try {
                knowledgeService.upload(agentId, fileName, bytes.length, bytes, stored.uri(), contentType);
                log.info("Seeded agent knowledge agentId={} fileName={}", agentId, fileName);
                return true;
            } catch (Exception e) {
                deleteStoredObjectQuietly(stored.uri());
                throw e;
            }
        } catch (Exception e) {
            log.warn("Failed to seed agent knowledge agentId={} fileName={}: {}", agentId, fileName, e.getMessage());
            return false;
        }
    }

    private boolean hasDocument(String agentId, String fileName) {
        List<AgentKnowledgeDocument> documents = knowledgeService.listDocuments(agentId);
        return documents.stream().anyMatch(document -> Objects.equals(document.fileName(), fileName));
    }

    private StoredObject putSeedObject(String agentId, String fileName, byte[] bytes, String contentType) throws Exception {
        String key = "knowledge-seeds/" + safePath(agentId) + "/"
            + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy/MM/dd"))
            + "/" + safePath(fileName);
        Path temp = Files.createTempFile("echomind-knowledge-seed-", extension(fileName));
        try {
            Files.write(temp, bytes);
            return storageService.putObject(key, temp, contentType);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private byte[] seedBytes(EchoMindProperties.KnowledgeSeed seed) throws Exception {
        String content = seed.getContent();
        if (content != null && !content.isBlank()) {
            return content.getBytes(StandardCharsets.UTF_8);
        }
        String location = trim(seed.getResource());
        if (location.isBlank()) {
            throw new IllegalArgumentException("knowledge seed must define resource or content");
        }
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new IllegalArgumentException("knowledge seed resource does not exist: " + location);
        }
        try (InputStream in = resource.getInputStream()) {
            return in.readAllBytes();
        }
    }

    private void deleteStoredObjectQuietly(String uri) {
        if (uri == null || uri.isBlank() || !storageService.supports(uri)) {
            return;
        }
        try {
            storageService.deleteObject(uri);
        } catch (Exception e) {
            log.warn("Failed to delete failed knowledge seed object uri={}: {}", uri, e.getMessage());
        }
    }

    private List<EchoMindProperties.KnowledgeSeed> seeds() {
        return config.getKnowledgeSeeds() == null ? List.of() : config.getKnowledgeSeeds();
    }

    private String safeFileName(String fileName) {
        String value = trim(fileName);
        return value.isBlank() ? "seed-knowledge.txt" : value;
    }

    private String safePath(String value) {
        String normalized = trim(value).replace("\\", "/");
        normalized = normalized.replaceAll("[^a-zA-Z0-9._/-]", "_");
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized.isBlank() ? "seed" : normalized;
    }

    private String contentType(String value) {
        String normalized = trim(value).toLowerCase(Locale.ROOT);
        return normalized.isBlank() ? "text/plain" : normalized;
    }

    private String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            String ext = fileName.substring(dot).toLowerCase(Locale.ROOT);
            if (ext.matches("\\.[a-z0-9]{1,12}")) {
                return ext;
            }
        }
        return ".txt";
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
