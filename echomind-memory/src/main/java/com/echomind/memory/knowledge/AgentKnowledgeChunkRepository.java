package com.echomind.memory.knowledge;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/** Agent 知识库切片 Repository。 */
public interface AgentKnowledgeChunkRepository extends JpaRepository<AgentKnowledgeChunkEntity, Long> {

    /** 查询指定 Agent 的全部切片，用于 MySQL 兜底检索。 */
    List<AgentKnowledgeChunkEntity> findByAgentId(String agentId);

    /** 按关键词粗召回候选切片，用于和向量召回做混合排序。 */
    @Query(value = """
        select *
        from echomind_agent_knowledge_chunks c
        where c.agent_id = :agentId
          and (
            lower(c.content) like :pattern escape '!'
            or lower(c.file_name) like :pattern escape '!'
          )
        order by c.created_at desc
        """, nativeQuery = true)
    List<AgentKnowledgeChunkEntity> searchKeywordCandidates(@Param("agentId") String agentId,
                                                            @Param("pattern") String pattern,
                                                            Pageable pageable);

    /** 查询指定文档的全部切片。 */
    List<AgentKnowledgeChunkEntity> findByDocumentId(Long documentId);

    /** 删除指定文档的全部切片。 */
    void deleteByDocumentId(Long documentId);

    /** 删除指定 Agent 的全部切片。 */
    void deleteByAgentId(String agentId);
}
