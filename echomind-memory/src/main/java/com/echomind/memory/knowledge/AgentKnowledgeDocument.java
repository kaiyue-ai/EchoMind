package com.echomind.memory.knowledge;

import java.time.Instant;

/** 前端展示用的 Agent 知识库文档视图。 */
public record AgentKnowledgeDocument(
    Long id,
    String agentId,
    String fileName,
    String fileType,
    long fileSize,
    int chunkCount,
    Instant createdAt
) {
    public static AgentKnowledgeDocument from(AgentKnowledgeDocumentEntity entity) {
        return new AgentKnowledgeDocument(
            entity.getId(),
            entity.getAgentId(),
            entity.getFileName(),
            entity.getFileType(),
            entity.getFileSize(),
            entity.getChunkCount(),
            entity.getCreatedAt()
        );
    }
}
