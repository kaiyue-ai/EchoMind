package com.echomind.agent.team.store;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Agent Team 定义。团队配置是 MySQL 事实来源。
 */
@Entity
@Table(
    name = "echomind_agent_teams",
    indexes = {
        @Index(name = "idx_agent_team_owner_time", columnList = "owner_user_id,created_at")
    }
)
@TableName("echomind_agent_teams")
@Getter
@Setter
public class TeamEntity {

    @Id
    @TableId(value = "team_id", type = IdType.INPUT)
    @Column(name = "team_id", length = 128)
    private String teamId;

    @TableField("owner_user_id")
    @Column(name = "owner_user_id", nullable = false, length = 128)
    private String ownerUserId = "default";

    @Column(nullable = false, length = 255)
    private String name;

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
