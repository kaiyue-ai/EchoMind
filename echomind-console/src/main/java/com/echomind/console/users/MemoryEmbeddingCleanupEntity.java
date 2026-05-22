package com.echomind.console.users;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** 仅用于管理端用户硬删除时按会话/消息清理旧向量备份。 */
@Entity
@Table(name = "echomind_memory_embeddings")
@Getter
@Setter
public class MemoryEmbeddingCleanupEntity {

    @Id
    private Long id;

    @Column(name = "session_id", nullable = false, length = 128)
    private String sessionId;

    @Column(name = "message_id", nullable = false)
    private Long messageId;
}
