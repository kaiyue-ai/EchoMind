package com.echomind.console.sensitive;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@TableName("echomind_sensitive_events")
@Getter
@Setter
public class SensitiveEventEntity {

    @TableId(value = "event_id", type = IdType.ASSIGN_UUID)
    private String eventId;

    @TableField("trace_id")
    private String traceId;

    @TableField("user_id")
    private String userId;

    private String username;

    @TableField("agent_id")
    private String agentId;

    @TableField("session_id")
    private String sessionId;

    @TableField("rule_id")
    private String ruleId;

    @TableField("rule_name")
    private String ruleName;

    private SensitiveDirection direction;

    private SensitiveAction action;

    @TableField("match_count")
    private int matchCount;

    private String sample;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
