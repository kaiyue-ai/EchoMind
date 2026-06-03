package com.echomind.memory.knowledge;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.echomind.memory.knowledge.entity.AgentKnowledgeDocumentEntity;

import java.time.Instant;

/** 前端展示用的 Agent 知识库文档视图。 */
public record AgentKnowledgeDocument(
    Long id,
    String agentId,
    String fileName,
    String fileType,
    long fileSize,
    int chunkCount,
    @JsonIgnore
    String objectUri,
    String contentType,
    boolean hasOriginalFile,
    Instant createdAt
) {
    public static AgentKnowledgeDocument from(AgentKnowledgeDocumentEntity entity) {
        String objectUri = entity.getObjectUri();
        return new AgentKnowledgeDocument(
            entity.getId(),
            entity.getAgentId(),
            entity.getFileName(),
            entity.getFileType(),
            entity.getFileSize(),
            entity.getChunkCount(),
            objectUri,
            entity.getContentType(),
            objectUri != null && !objectUri.isBlank(),
            entity.getCreatedAt()
        );
    }
}
