package com.echomind.console.quota;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@TableName("echomind_token_quotas")
@Getter
@Setter
public class TokenQuotaEntity {

    @TableId(value = "user_id", type = IdType.INPUT)
    private String userId;

    @TableField("daily_limit_tokens")
    private Long dailyLimitTokens;

    @TableField("monthly_limit_tokens")
    private Long monthlyLimitTokens;

    @TableField("warning_threshold_percent")
    private int warningThresholdPercent = 80;

    private TokenQuotaStatus status = TokenQuotaStatus.ACTIVE;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
