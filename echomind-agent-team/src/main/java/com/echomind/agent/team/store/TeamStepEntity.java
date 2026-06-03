package com.echomind.agent.team.store;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.echomind.agent.team.state.TeamStepStatus;
import com.echomind.agent.team.state.TeamRiskLevel;
import com.echomind.agent.team.state.TeamStepQualityStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Planner 拆出的子任务卡片。
 */
@TableName("echomind_agent_team_steps")
@Getter
@Setter
public class TeamStepEntity {

    @TableId(value = "step_id", type = IdType.INPUT)
    private String stepId;

    @TableField("run_id")
    private String runId;

    @TableField("step_index")
    private int stepIndex;

    @TableField("client_step_id")
    private String clientStepId;

    private String title;

    private String description;

    @TableField("required_capabilities_json")
    private String requiredCapabilitiesJson;

    @TableField("depends_on_step_ids_json")
    private String dependsOnStepIdsJson;

    @TableField("risk_level")
    private TeamRiskLevel riskLevel = TeamRiskLevel.LOW;

    @TableField("risk_reason")
    private String riskReason;

    @TableField("acceptance_criteria")
    private String acceptanceCriteria;

    @TableField("assigned_agent_id")
    private String assignedAgentId;

    private TeamStepStatus status = TeamStepStatus.PENDING;

    @TableField("input_json")
    private String inputJson;

    @TableField("raw_output")
    private String rawOutput;

    @TableField("previous_outputs_json")
    private String previousOutputsJson;

    @TableField("revision_instructions")
    private String revisionInstructions;

    @TableField("review_status")
    private String reviewStatus;

    @TableField("quality_status")
    private TeamStepQualityStatus qualityStatus = TeamStepQualityStatus.PENDING;

    @TableField("sub_review_json")
    private String subReviewJson;

    @TableField("last_review_reason")
    private String lastReviewReason;

    @TableField("reflection_json")
    private String reflectionJson;

    @TableField("plan_iteration")
    private int planIteration;

    @TableField("retry_count")
    private int retryCount;// 重试的次数

    @TableField("started_at")
    private Instant startedAt;

    @TableField("completed_at")
    private Instant completedAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
