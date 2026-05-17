package com.echomind.memory.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 会话记忆主表。
 *
 * <p>一条记录对应前端的一次聊天会话。MySQL 是正式历史的事实来源：
 * 页面历史、摘要、消息总数都以这里和消息表为准；Redis 只做近期上下文缓存。</p>
 */
@Entity
@Table(name = "echomind_chat_sessions")
@IdClass(ChatSessionId.class)
@Getter
@Setter
public class ChatSessionEntity {

    /** 会话所属用户。旧数据默认归属 default。 */
    @Id
    @Column(name = "user_id", nullable = false, length = 128)
    private String userId = "default";

    /** 会话标识，通常来自前端 sessionId，同一个值可在不同用户下复用。 */
    @Id
    @Column(name = "session_id", length = 128)
    private String sessionId;

    /** 当前会话绑定的 Agent，允许为空以兼容旧入口。 */
    @Column(name = "agent_id", length = 128)
    private String agentId;

    /** 用第一条用户消息生成的简短标题，供会话列表展示。 */
    @Column(length = 255)
    private String title;

    /** 压缩后的早期对话摘要，用于提示词上下文。 */
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String summary;

    /** 该会话已保存的消息数量。 */
    @Column(name = "message_count", nullable = false)
    private int messageCount;

    /** 会话创建时间。 */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** 最近一次消息写入时间。 */
    @Column(name = "last_activity", nullable = false)
    private Instant lastActivity;

    /** 元数据更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (userId == null || userId.isBlank()) {
            userId = "default";
        }
        if (createdAt == null) {
            createdAt = now;
        }
        if (lastActivity == null) {
            lastActivity = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        if (userId == null || userId.isBlank()) {
            userId = "default";
        }
        updatedAt = Instant.now();
    }
}
