package com.echomind.agent.team.store;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.echomind.agent.team.state.TeamClarificationStage;
import com.echomind.agent.team.state.TeamRunStatus;
import com.echomind.agent.team.state.TeamTaskLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 一次团队协作任务，是 Team 黑板的顶层记录。
 */
@Entity
@Table(name = "echomind_agent_team_runs")
@TableName("echomind_agent_team_runs")
@Getter
@Setter
public class TeamRunEntity {

    @Id
    @TableId(value = "run_id", type = IdType.INPUT)
    @Column(name = "run_id", length = 128)
    // 任务id
    private String runId;

    @TableField("team_id")
    @Column(name = "team_id", nullable = false, length = 128)
    // 团队id
    private String teamId;

    @TableField("user_id")
    @Column(name = "user_id", nullable = false, length = 128)
    // 用户id
    private String userId = "default";

    // 任务id
    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String task;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    // 任务状态
    private TeamRunStatus status = TeamRunStatus.PENDING;

    @TableField("task_level")
    @Enumerated(EnumType.STRING)
    @Column(name = "task_level", nullable = false, length = 32)
    // 任务等级
    private TeamTaskLevel taskLevel = TeamTaskLevel.COMPLEX;

    @TableField("clarification_question")
    @Lob
    @Column(name = "clarification_question", columnDefinition = "LONGTEXT")
    // 任务澄清问题
    private String clarificationQuestion;

    @TableField("clarification_answer")
    @Lob
    @Column(name = "clarification_answer", columnDefinition = "LONGTEXT")
    // 任务澄清回答
    private String clarificationAnswer;

    @TableField("clarification_stage")
    @Enumerated(EnumType.STRING)
    @Column(name = "clarification_stage", length = 40)
    // 任务澄清阶段
    private TeamClarificationStage clarificationStage;

    @TableField("plan_review_json")
    @Lob
    @Column(name = "plan_review_json", columnDefinition = "LONGTEXT")
    // 任务计划审核json
    private String planReviewJson;

    @TableField("result_review_json")
    @Lob
    @Column(name = "result_review_json", columnDefinition = "LONGTEXT")
    // 任务结果审核json
    private String resultReviewJson;

    @TableField("merge_output")
    @Lob
    @Column(name = "merge_output", columnDefinition = "LONGTEXT")
    // 任务合并输出
    private String mergeOutput;

    @TableField("global_review_json")
    @Lob
    @Column(name = "global_review_json", columnDefinition = "LONGTEXT")
    // 任务全局审核json
    private String globalReviewJson;

    @TableField("conflict_report_json")
    @Lob
    @Column(name = "conflict_report_json", columnDefinition = "LONGTEXT")
    // 任务冲突报告json
    private String conflictReportJson;
    // 任务仲裁json
    @TableField("arbitration_json")
    @Lob
    @Column(name = "arbitration_json", columnDefinition = "LONGTEXT")
    private String arbitrationJson;

    @TableField("final_output")
    @Lob
    @Column(name = "final_output", columnDefinition = "LONGTEXT")
    // 任务最终输出
    private String finalOutput;
    // 任务mermaid图
    @TableField("mermaid_diagram")
    @Lob
    @Column(name = "mermaid_diagram", columnDefinition = "LONGTEXT")
    // 任务mermaid图
    private String mermaidDiagram;

    @TableField("plan_retry_count")
    @Column(name = "plan_retry_count", nullable = false)
    // 任务计划重试次数
    private int planRetryCount;

    @TableField("result_replan_count")
    @Column(name = "result_replan_count", nullable = false)
    // 任务结果重新计划次数
    private int resultReplanCount;

    @TableField("partial_replan_count")
    @Column(name = "partial_replan_count", nullable = false)
    // 任务部分重新计划次数
    private int partialReplanCount;

    @TableField("full_replan_count")    
    @Column(name = "full_replan_count", nullable = false)
    // 任务完整重新计划次数
    private int fullReplanCount;
    // 任务仲裁次数

    @TableField("arbitration_count")
    @Column(name = "arbitration_count", nullable = false)
    private int arbitrationCount;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

}
