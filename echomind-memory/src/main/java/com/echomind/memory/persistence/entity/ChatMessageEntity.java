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
 * 会话消息表。
 *
 * <p>这里保存完整聊天流水，包括用户消息、模型回复、工具结果和附件引用。
 * 它是历史记录接口的来源，也是向量索引回溯原文的来源。</p>
 */
@TableName("echomind_chat_messages")
@Getter
@Setter
public class ChatMessageEntity {

    /** 自增主键，作为向量表关联消息的稳定 ID。 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 所属会话 ID。 */
    @TableField("session_id")
    private String sessionId;

    /** 消息所属用户。旧数据默认归属 default。 */
    @TableField("user_id")
    private String userId = "default";

    /** 消息角色：user / assistant / system / tool。 */
    private String role;

    /** 消息正文。 */
    private String content;

    /** AgentMessage 的时间戳，历史展示按它排序。 */
    private Instant timestamp;

    /** 元数据 JSON，例如 toolCallId。 */
    @TableField("metadata_json")
    private String metadataJson;

    /** 附件 JSON，仅保存对象存储引用和 URL，不保存二进制内容。 */
    @TableField("attachments_json")
    private String attachmentsJson;

    /** 数据入库时间。 */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
