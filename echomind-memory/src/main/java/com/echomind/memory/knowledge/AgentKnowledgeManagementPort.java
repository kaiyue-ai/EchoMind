package com.echomind.memory.knowledge;

import java.util.List;

/**
 * Agent 知识库管理入口。
 *
 * <p>Console 等管理层只依赖这个轻量接口，避免单元测试和上层用例直接加载
 * Milvus 具体实现。</p>
 */
public interface AgentKnowledgeManagementPort {

    List<AgentKnowledgeDocument> listDocuments(String agentId);

    default AgentKnowledgeDocument upload(String agentId, String fileName, long fileSize, byte[] bytes) {
        return upload(agentId, fileName, fileSize, bytes, null, null);
    }

    AgentKnowledgeDocument upload(String agentId, String fileName, long fileSize, byte[] bytes,
                                  String objectUri, String contentType);

    void deleteDocument(String agentId, Long documentId);

    void deleteAll(String agentId);
}
