package com.echomind.agent.team.store;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.echomind.agent.team.state.TeamRole;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Agent Team 成员及其能力标签。
 */
@TableName("echomind_agent_team_members")
@Getter
@Setter
public class TeamMemberEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("team_id")
    private String teamId;

    @TableField("agent_id")
    private String agentId;

    private TeamRole role;

    @TableField("capability_tags_json")
    private String capabilityTagsJson;

    @TableField("sort_order")
    private int sortOrder;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
