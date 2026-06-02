package com.echomind.agent.team.store;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.echomind.agent.team.state.TeamEventType;
import com.echomind.agent.team.state.TeamRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Team 黑板事件流，用于审计、轮询看板和 Mermaid 生成。
 */
@Entity
@Table(
    name = "echomind_agent_team_events",
    indexes = {
        @Index(name = "idx_team_event_run", columnList = "run_id,created_at"),
        @Index(name = "idx_team_event_step", columnList = "step_id")
    }
)
@TableName("echomind_agent_team_events")
@Getter
@Setter
public class TeamEventEntity {

    @Id
    @TableId(value = "id", type = IdType.AUTO)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TableField("run_id")
    @Column(name = "run_id", nullable = false, length = 128)
    private String runId;

    @TableField("step_id")
    @Column(name = "step_id", length = 128)
    private String stepId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 64)
    private TeamEventType type;

    @TableField("actor_role")
    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role", length = 32)
    private TeamRole actorRole;

    @TableField("actor_agent_id")
    @Column(name = "actor_agent_id", length = 128)
    private String actorAgentId;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String message;

    @TableField("payload_json")
    @Lob
    @Column(name = "payload_json", columnDefinition = "LONGTEXT")
    private String payloadJson;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
