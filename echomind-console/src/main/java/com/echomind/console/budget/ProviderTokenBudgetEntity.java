package com.echomind.console.budget;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@TableName("echomind_provider_token_budgets")
@Getter
@Setter
public class ProviderTokenBudgetEntity {

    @TableId(value = "provider_id", type = IdType.INPUT)
    private String providerId;

    @TableField("daily_limit_tokens")
    private Long dailyLimitTokens;

    @TableField("weekly_limit_tokens")
    private Long weeklyLimitTokens;

    @TableField("monthly_limit_tokens")
    private Long monthlyLimitTokens;

    @TableField("warning_threshold_percent")
    private int warningThresholdPercent = 80;

    private ProviderTokenBudgetStatus status = ProviderTokenBudgetStatus.ACTIVE;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
