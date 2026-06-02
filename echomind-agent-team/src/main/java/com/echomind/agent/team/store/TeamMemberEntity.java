package com.echomind.agent.team.store;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
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
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Agent Team 成员及其能力标签。
 */
@Entity
@Table(
    name = "echomind_agent_team_members",
    indexes = {
        @Index(name = "idx_team_member_team", columnList = "team_id"),
        @Index(name = "idx_team_member_agent", columnList = "agent_id")
    }
)
@TableName("echomind_agent_team_members")
@Getter
@Setter
public class TeamMemberEntity {

    @Id
    @TableId(value = "id", type = IdType.AUTO)
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TableField("team_id")
    @Column(name = "team_id", nullable = false, length = 128)
    private String teamId;

    @TableField("agent_id")
    @Column(name = "agent_id", nullable = false, length = 128)
    private String agentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TeamRole role;

    @TableField("capability_tags_json")
    @Lob
    @Column(name = "capability_tags_json", columnDefinition = "LONGTEXT")
    private String capabilityTagsJson;

    @TableField("sort_order")
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
