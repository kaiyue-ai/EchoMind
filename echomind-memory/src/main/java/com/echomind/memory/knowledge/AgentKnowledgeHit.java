package com.echomind.memory.knowledge;

/** Agent 知识库召回命中的切片。 */
public record AgentKnowledgeHit(
    Long chunkId,
    Long documentId,
    String fileName,
    int chunkIndex,
    String content,
    double score
) {}
