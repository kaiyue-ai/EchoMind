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
    private String ruleId;

    @TableField("alert_type")
    private AlertType alertType;

    @TableField("rule_name")
    private String ruleName;

    private AlertSeverity severity = AlertSeverity.WARNING;

    private boolean enabled = true;

    @TableField("threshold_percent")
    private Double thresholdPercent;

    @TableField("window_minutes")
    private Integer windowMinutes;

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
