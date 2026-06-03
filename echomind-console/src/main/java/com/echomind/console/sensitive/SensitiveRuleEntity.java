package com.echomind.console.sensitive;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@TableName("echomind_sensitive_rules")
@Getter
@Setter
public class SensitiveRuleEntity {

    @TableId(value = "rule_id", type = IdType.ASSIGN_UUID)
    private String ruleId;

    @TableField("rule_name")
    private String ruleName;

    private String pattern;

    private String replacement;

    private SensitiveAction action = SensitiveAction.MASK;

    private boolean enabled = true;

    @TableField("built_in")
    private boolean builtIn;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
