package com.echomind.agent.team.store;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Agent Team 定义。团队配置是 MySQL 事实来源。
 */
@TableName("echomind_agent_teams")
@Getter
@Setter
public class TeamEntity {

    @TableId(value = "team_id", type = IdType.INPUT)
    // 团队id
    private String teamId;

    @TableField("owner_user_id")
    // 团队所属用户
    private String ownerUserId = "default";

    // 团队名称
    private String name;

    // 创建时间
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    // 更新时间
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
