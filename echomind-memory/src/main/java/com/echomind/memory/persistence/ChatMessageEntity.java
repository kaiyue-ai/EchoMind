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
 * 会话消息表。
 *
 * <p>这里保存完整聊天流水，包括用户消息、模型回复、工具结果和附件引用。
 * 它是历史记录接口的来源，也是向量索引回溯原文的来源。</p>
 */
@Entity
@Table(
    name = "echomind_chat_messages",
    indexes = {
        @Index(name = "idx_chat_msg_session_time", columnList = "session_id,timestamp"),
        @Index(name = "idx_chat_msg_session_role", columnList = "session_id,role")
    }
)
@Getter
@Setter
public class ChatMessageEntity {

    /** 自增主键，作为向量表关联消息的稳定 ID。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属会话 ID。 */
    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    /** 消息角色：user / assistant / system / tool。 */
    @Column(nullable = false, length = 32)
    private String role;

    /** 消息正文。 */
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    /** AgentMessage 的时间戳，历史展示按它排序。 */
    @Column(nullable = false)
    private Instant timestamp;

    /** 元数据 JSON，例如 toolCallId。 */
    @Lob
    @Column(name = "metadata_json", columnDefinition = "LONGTEXT")
    private String metadataJson;

    /** 附件 JSON，仅保存对象存储引用和 URL，不保存二进制内容。 */
    @Lob
    @Column(name = "attachments_json", columnDefinition = "LONGTEXT")
    private String attachmentsJson;

    /** 数据入库时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (timestamp == null) {
            timestamp = Instant.now();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
