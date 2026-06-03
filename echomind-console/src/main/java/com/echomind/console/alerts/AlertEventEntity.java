package com.echomind.console.alerts;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@TableName("echomind_alert_events")
@Getter
@Setter
public class AlertEventEntity {

    @TableId(value = "event_id", type = IdType.ASSIGN_UUID)
    private String eventId;

    @TableField("alert_type")
    private AlertType alertType;

    private AlertSeverity severity;

    private AlertStatus status;

    @TableField("trace_id")
    private String traceId;

    @TableField("user_id")
    private String userId;

    private String username;

    @TableField("agent_id")
    private String agentId;

    @TableField("session_id")
    private String sessionId;

    @TableField("provider_id")
    private String providerId;

    private String title;

    private String message;

    private String suggestion;

    @TableField("failure_reason")
    private String failureReason;

    private boolean escalated = false;

    @TableField("suppressed_count")
    private int suppressedCount = 0;

    @TableField("provider_response")
    private String providerResponse;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
