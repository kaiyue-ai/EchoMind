package com.echomind.agent.team.store;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.echomind.agent.team.state.TeamRunStatus;
import com.echomind.agent.team.state.TeamTaskLevel;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * 一次团队协作任务，是 Team 黑板的顶层记录。
 */
@TableName("echomind_agent_team_runs")
@Getter
@Setter
public class TeamRunEntity {

    @TableId(value = "run_id", type = IdType.INPUT)
    // 任务id
    private String runId;

    @TableField("team_id")
    // 团队id
    private String teamId;

    @TableField("user_id")
    // 用户id
    private String userId = "default";

    // 任务id
    private String task;

    // 任务状态
    private TeamRunStatus status = TeamRunStatus.PENDING;

    @TableField("task_level")
    // 任务等级
    private TeamTaskLevel taskLevel = TeamTaskLevel.COMPLEX;

    @TableField("plan_review_enabled")
    private boolean planReviewEnabled = true;

    @TableField("sub_review_enabled")
    private boolean subReviewEnabled = true;

    @TableField("global_review_enabled")
    private boolean globalReviewEnabled = true;

    @TableField("simple_fast_path_enabled")
    private boolean simpleFastPathEnabled;

    @TableField("plan_review_json")
    // 任务计划审核json
    private String planReviewJson;

    @TableField("result_review_json")
    // 任务结果审核json
    private String resultReviewJson;

    @TableField("merge_output")
    // 任务合并输出
    private String mergeOutput;

    @TableField("global_review_json")
    // 任务全局审核json
    private String globalReviewJson;

    @TableField("conflict_report_json")
    // 任务冲突报告json
    private String conflictReportJson;
    // 任务仲裁json
    @TableField("arbitration_json")
    private String arbitrationJson;

    @TableField("final_output")
    // 任务最终输出
    private String finalOutput;
    // 任务mermaid图
    @TableField("mermaid_diagram")
    // 任务mermaid图
    private String mermaidDiagram;

    @TableField("plan_retry_count")
    // 任务计划重试次数
    private int planRetryCount;

    @TableField("result_replan_count")
    // 任务结果重新计划次数
    private int resultReplanCount;

    @TableField("partial_replan_count")
    // 任务部分重新计划次数
    private int partialReplanCount;

    @TableField("full_replan_count")    
    // 任务完整重新计划次数
    private int fullReplanCount;
    // 任务仲裁次数

    @TableField("arbitration_count")
    private int arbitrationCount;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;

}
