package com.echomind.agent.team.store;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.echomind.agent.team.state.TeamEventType;
import com.echomind.agent.team.state.TeamRole;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Team 黑板事件流，用于审计、轮询看板和 Mermaid 生成。
 */
@TableName("echomind_agent_team_events")
@Getter
@Setter
public class TeamEventEntity {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("run_id")
    private String runId;

    @TableField("step_id")
    private String stepId;

    private TeamEventType type;

    @TableField("actor_role")
    private TeamRole actorRole;

    @TableField("actor_agent_id")
    private String actorAgentId;

    private String message;

    @TableField("payload_json")
    private String payloadJson;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;
}
