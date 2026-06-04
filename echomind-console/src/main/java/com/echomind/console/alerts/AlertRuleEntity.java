package com.echomind.console.alerts;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@TableName("echomind_alert_rules")
@Getter
@Setter
public class AlertRuleEntity {

    @TableId(value = "rule_id", type = IdType.ASSIGN_UUID)
    private String ruleId; // 规则id

    @TableField("alert_type")
    private AlertType alertType; // 警告类型

    @TableField("rule_name")
    private String ruleName; // 规则名称

    private AlertSeverity severity = AlertSeverity.WARNING; // 警告的严重性

    private boolean enabled = true; // 是否启用

    @TableField("threshold_percent")
    private Double thresholdPercent; // 警告阈值百分比

    @TableField("window_minutes")
    private Integer windowMinutes; // 警告窗口分钟值

    @TableField("quiet_minutes")
    private int quietMinutes = 30;

    @TableField("escalation_enabled")
    private boolean escalationEnabled = true;

    @TableField("escalation_threshold")
    private int escalationThreshold = 3;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
