package com.echomind.agent.team.store;

import com.echomind.agent.team.state.TeamClarificationStage;
import com.echomind.agent.team.state.TeamRunStatus;
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
 * 一次团队协作任务，是 Team 黑板的顶层记录。
 */
@Entity
@Table(
    name = "echomind_agent_team_runs",
    indexes = {
        @Index(name = "idx_team_run_team", columnList = "team_id"),
        @Index(name = "idx_team_run_status", columnList = "status")
    }
)
@Getter
@Setter
public class TeamRunEntity {

    @Id
    @Column(name = "run_id", length = 128)
    private String runId;

    @Column(name = "team_id", nullable = false, length = 128)
    private String teamId;

    @Lob
    @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String task;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private TeamRunStatus status;

    @Lob
    @Column(name = "clarification_question", columnDefinition = "LONGTEXT")
    private String clarificationQuestion;

    @Lob
    @Column(name = "clarification_answer", columnDefinition = "LONGTEXT")
    private String clarificationAnswer;

    @Enumerated(EnumType.STRING)
    @Column(name = "clarification_stage", length = 40)
    private TeamClarificationStage clarificationStage;

    @Lob
    @Column(name = "plan_review_json", columnDefinition = "LONGTEXT")
    private String planReviewJson;

    @Lob
    @Column(name = "result_review_json", columnDefinition = "LONGTEXT")
    private String resultReviewJson;

    @Lob
    @Column(name = "final_output", columnDefinition = "LONGTEXT")
    private String finalOutput;

    @Lob
    @Column(name = "mermaid_diagram", columnDefinition = "LONGTEXT")
    private String mermaidDiagram;

    @Column(name = "plan_retry_count", nullable = false)
    private int planRetryCount;

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
            status = TeamRunStatus.PENDING;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
