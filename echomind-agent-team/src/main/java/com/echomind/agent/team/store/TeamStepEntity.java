package com.echomind.agent.team.store;

import com.echomind.agent.team.state.TeamStepStatus;
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
@Getter
@Setter
public class TeamStepEntity {

    @Id
    @Column(name = "step_id", length = 128)
    private String stepId;

    @Column(name = "run_id", nullable = false, length = 128)
    private String runId;

    @Column(name = "step_index", nullable = false)
    private int stepIndex;

    @Column(nullable = false, length = 255)
    private String title;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String description;

    @Lob
    @Column(name = "required_capabilities_json", columnDefinition = "LONGTEXT")
    private String requiredCapabilitiesJson;

    @Lob
    @Column(name = "acceptance_criteria", columnDefinition = "LONGTEXT")
    private String acceptanceCriteria;

    @Column(name = "assigned_agent_id", length = 128)
    private String assignedAgentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private TeamStepStatus status;

    @Lob
    @Column(name = "input_json", columnDefinition = "LONGTEXT")
    private String inputJson;

    @Lob
    @Column(name = "raw_output", columnDefinition = "LONGTEXT")
    private String rawOutput;

    @Lob
    @Column(name = "previous_outputs_json", columnDefinition = "LONGTEXT")
    private String previousOutputsJson;

    @Lob
    @Column(name = "revision_instructions", columnDefinition = "LONGTEXT")
    private String revisionInstructions;

    @Column(name = "review_status", length = 32)
    private String reviewStatus;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
