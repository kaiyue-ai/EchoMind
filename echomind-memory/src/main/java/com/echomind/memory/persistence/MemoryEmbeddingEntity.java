package com.echomind.memory.persistence;

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
 * 历史会话消息向量表。
 *
 * <p>当前正式架构中，普通聊天向量只写 Redis Stack；MySQL 保留完整聊天消息给前端展示和审计。
 * 该表仅为旧数据和旧线性实现保留，不作为 LLM 上下文事实来源。</p>
 */
@Entity
@Table(
    name = "echomind_memory_embeddings",
    indexes = {
        @Index(name = "idx_memory_embedding_session", columnList = "session_id"),
        @Index(name = "idx_memory_embedding_message", columnList = "message_id")
    }
)
@Getter
@Setter
public class MemoryEmbeddingEntity {

    /** 自增主键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属会话 ID。 */
    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    /** 对应的消息表 ID。 */
    @Column(name = "message_id", nullable = false)
    private Long messageId;

    /** 被索引消息的角色，主要是 user / assistant。 */
    @Column(nullable = false, length = 32)
    private String role;

    /** 检索命中后注入提示词的短文本。 */
    @Column(name = "content_preview", length = 1000)
    private String contentPreview;

    /** 向量 JSON 数组，由 tongyi-embedding-vision-plus 生成。 */
    @Lob
    @Column(name = "embedding_json", nullable = false, columnDefinition = "LONGTEXT")
    private String embeddingJson;

    /** 向量创建时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
