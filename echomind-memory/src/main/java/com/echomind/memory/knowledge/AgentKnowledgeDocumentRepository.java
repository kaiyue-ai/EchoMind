package com.echomind.memory.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Agent 知识库文档 Repository。 */
public interface AgentKnowledgeDocumentRepository extends JpaRepository<AgentKnowledgeDocumentEntity, Long> {

    /** 查询指定 Agent 的所有知识库文档。 */
    List<AgentKnowledgeDocumentEntity> findByAgentIdOrderByCreatedAtDesc(String agentId);
}
