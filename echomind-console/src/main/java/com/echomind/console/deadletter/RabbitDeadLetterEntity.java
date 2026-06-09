package com.echomind.console.deadletter;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@TableName("echomind_rabbitmq_dead_letters")
@Getter
@Setter
public class RabbitDeadLetterEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("message_hash")
    private String messageHash;

    @TableField("dlq_name")
    private String dlqName;

    @TableField("message_type")
    private String messageType;

    @TableField("business_key")
    private String businessKey;

    @TableField("trace_id")
    private String traceId;

    @TableField("payload_json")
    private String payloadJson;

    @TableField("error_headers_json")
    private String errorHeadersJson;

    private String status = RabbitDeadLetterStatus.ARCHIVED;

    @TableField("replay_count")
    private int replayCount;

    @TableField("last_replay_error")
    private String lastReplayError;

    @TableField("archived_at")
    private Instant archivedAt;

    @TableField("replayed_at")
    private Instant replayedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
