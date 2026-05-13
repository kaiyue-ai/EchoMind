package com.echomind.memory.knowledge;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Agent 知识库文档表。
 *
 * <p>一个 Agent 可以拥有多份 txt/pdf 文档。文档表保存文件级元数据，
 * 真正进入向量检索的是 {@link AgentKnowledgeChunkEntity} 中的切片。</p>
 */
@Entity
@Table(
    name = "echomind_agent_knowledge_documents",
    indexes = {
        @Index(name = "idx_agent_knowledge_doc_agent", columnList = "agent_id")
    }
)
@Getter
@Setter
public class AgentKnowledgeDocumentEntity {

    /** 文档自增 ID。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属 Agent。 */
    @Column(name = "agent_id", nullable = false, length = 128)
    private String agentId;

    /** 用户上传时的文件名。 */
    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    /** 文件类型，目前支持 txt/pdf。 */
    @Column(name = "file_type", nullable = false, length = 32)
    private String fileType;

    /** 原始文件大小，单位字节。 */
    @Column(name = "file_size", nullable = false)
    private long fileSize;

    /** 成功切出来的片段数。 */
    @Column(name = "chunk_count", nullable = false)
    private int chunkCount;

    /** 创建时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
