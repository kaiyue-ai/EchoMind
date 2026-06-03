package com.echomind.memory.persistence.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 会话记忆主表。
 *
 * <p>一条记录对应前端的一次聊天会话。MySQL 是正式历史的事实来源：
 * 页面历史、摘要、消息总数都以这里和消息表为准；Redis 只做近期上下文缓存。</p>
 */
@TableName("echomind_chat_sessions")
@Getter
@Setter
public class ChatSessionEntity {

    /** 会话所属用户。旧数据默认归属 default。 */
    @TableField("user_id")
    private String userId = "default";

    /** 会话标识，通常来自前端 sessionId，同一个值可在不同用户下复用。 */
    @TableId(value = "session_id", type = IdType.INPUT)
    private String sessionId;

    /** 当前会话绑定的 Agent，允许为空以兼容旧入口。 */
    @TableField("agent_id")
    private String agentId;

    /** 用第一条用户消息生成的简短标题，供会话列表展示。 */
    private String title;

    /** 压缩后的早期对话摘要，用于提示词上下文。 */
    private String summary;

    /** 该会话已保存的消息数量。 */
    @TableField("message_count")
    private int messageCount;

    /** 会话创建时间。 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    /** 最近一次消息写入时间。 */
    @TableField("last_activity")
    private Instant lastActivity;

    /** 元数据更新时间。 */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
