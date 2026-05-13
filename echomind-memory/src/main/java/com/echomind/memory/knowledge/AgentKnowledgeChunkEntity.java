package com.echomind.memory.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Agent 知识库切片表。
 *
 * <p>这里保存的是可召回的最小知识单元：每个切片拥有正文、顺序号和向量 JSON。
 * Redis Stack 是在线检索索引；MySQL 中的向量 JSON 用作持久备份和兜底。</p>
 */
@Entity
@Table(
    name = "echomind_agent_knowledge_chunks",
    indexes = {
        @Index(name = "idx_agent_knowledge_chunk_agent", columnList = "agent_id"),
        @Index(name = "idx_agent_knowledge_chunk_doc", columnList = "document_id")
    }
)
@Getter
@Setter
public class AgentKnowledgeChunkEntity {

    /** 切片自增 ID。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属 Agent。 */
    @Column(name = "agent_id", nullable = false, length = 128)
    private String agentId;

    /** 所属文档 ID。 */
    @Column(name = "document_id", nullable = false)
    private Long documentId;

    /** 原始文件名，召回后用于给模型提示来源。 */
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** 文档内切片序号，从 0 开始。 */
    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    /** 切片正文。 */
    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    /** 向量 JSON 数组。 */
    @Lob
    @Column(name = "embedding_json", nullable = false, columnDefinition = "LONGTEXT")
    private String embeddingJson;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
