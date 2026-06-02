package com.echomind.agent.team.store;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.echomind.agent.team.state.TeamStepStatus;
import com.echomind.agent.team.state.TeamRiskLevel;
import com.echomind.agent.team.state.TeamStepQualityStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * Planner 拆出的子任务卡片。
 */
@Entity
@Table(
    name = "echomind_agent_team_steps",
    indexes = {
        @Index(name = "idx_team_step_run", columnList = "run_id"),
        @Index(name = "idx_team_step_status", columnList = "status"),
        @Index(name = "idx_team_step_agent", columnList = "assigned_agent_id")
    }
)
@TableName("echomind_agent_team_steps")
@Getter
@Setter
public class TeamStepEntity {

    @Id
    @TableId(value = "step_id", type = IdType.INPUT)
    @Column(name = "step_id", length = 128)
    private String stepId;

    @TableField("run_id")
    @Column(name = "run_id", nullable = false, length = 128)
    private String runId;

    @TableField("step_index")
    @Column(name = "step_index", nullable = false)
    private int stepIndex;

    @TableField("client_step_id")
    @Column(name = "client_step_id", length = 128)
    private String clientStepId;

    @Column(nullable = false, length = 255)
    private String title;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String description;

    @TableField("required_capabilities_json")
    @Lob
    @Column(name = "required_capabilities_json", columnDefinition = "LONGTEXT")
    private String requiredCapabilitiesJson;

    @TableField("depends_on_step_ids_json")
    @Lob
    @Column(name = "depends_on_step_ids_json", columnDefinition = "LONGTEXT")
    private String dependsOnStepIdsJson;

    @TableField("risk_level")
    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false, length = 32)
    private TeamRiskLevel riskLevel = TeamRiskLevel.LOW;

    @TableField("risk_reason")
    @Lob
    @Column(name = "risk_reason", columnDefinition = "LONGTEXT")
    private String riskReason;

    @TableField("acceptance_criteria")
    @Lob
    @Column(name = "acceptance_criteria", columnDefinition = "LONGTEXT")
    private String acceptanceCriteria;

    @TableField("assigned_agent_id")
    @Column(name = "assigned_agent_id", length = 128)
    private String assignedAgentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TeamStepStatus status;

    @TableField("input_json")
    @Lob
    @Column(name = "input_json", columnDefinition = "LONGTEXT")
    private String inputJson;

    @TableField("raw_output")
    @Lob
    @Column(name = "raw_output", columnDefinition = "LONGTEXT")
    private String rawOutput;

    @TableField("previous_outputs_json")
    @Lob
    @Column(name = "previous_outputs_json", columnDefinition = "LONGTEXT")
    private String previousOutputsJson;

    @TableField("revision_instructions")
    @Lob
    @Column(name = "revision_instructions", columnDefinition = "LONGTEXT")
    private String revisionInstructions;

    @TableField("review_status")
    @Column(name = "review_status", length = 32)
    private String reviewStatus;

    @TableField("quality_status")
    @Enumerated(EnumType.STRING)
    @Column(name = "quality_status", nullable = false, length = 32)
    private TeamStepQualityStatus qualityStatus = TeamStepQualityStatus.PENDING;

    @TableField("sub_review_json")
    @Lob
    @Column(name = "sub_review_json", columnDefinition = "LONGTEXT")
    private String subReviewJson;

    @TableField("last_review_reason")
    @Lob
    @Column(name = "last_review_reason", columnDefinition = "LONGTEXT")
    private String lastReviewReason;

    @TableField("reflection_json")
    @Lob
    @Column(name = "reflection_json", columnDefinition = "LONGTEXT")
    private String reflectionJson;

    @TableField("plan_iteration")
    @Column(name = "plan_iteration", nullable = false)
    private int planIteration;

    @TableField("retry_count")
    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @TableField("started_at")
    @Column(name = "started_at")
    private Instant startedAt;

    @TableField("completed_at")
    @Column(name = "completed_at")
    private Instant completedAt;

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
        if (status == null) {
            status = TeamStepStatus.PENDING;
        }
        if (riskLevel == null) {
            riskLevel = TeamRiskLevel.LOW;
        }
        if (qualityStatus == null) {
            qualityStatus = TeamStepQualityStatus.PENDING;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
