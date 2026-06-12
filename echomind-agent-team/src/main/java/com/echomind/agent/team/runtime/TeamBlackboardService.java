package com.echomind.agent.team.runtime;

import com.echomind.agent.Agent;
import com.echomind.agent.AgentFactory;
import com.echomind.agent.orchestration.AgentOrchestrator;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.team.messaging.RunStarted;
import com.echomind.agent.team.model.PlannedStep;
import com.echomind.agent.team.model.ReviewerDecision;
import com.echomind.agent.team.model.StepReflection;
import com.echomind.agent.team.model.TeamMember;
import com.echomind.agent.team.state.ReviewerAction;
import com.echomind.agent.team.state.TeamEventType;
import com.echomind.agent.team.state.TeamRiskLevel;
import com.echomind.agent.team.state.TeamRole;
import com.echomind.agent.team.state.TeamRunStatus;
import com.echomind.agent.team.state.TeamStepQualityStatus;
import com.echomind.agent.team.state.TeamStepStatus;
import com.echomind.agent.team.state.TeamTaskLevel;
import com.echomind.agent.team.store.TeamEntity;
import com.echomind.agent.team.store.TeamEventEntity;
import com.echomind.agent.team.store.TeamEventMapper;
import com.echomind.agent.team.store.TeamMapper;
import com.echomind.agent.team.store.TeamMemberEntity;
import com.echomind.agent.team.store.TeamMemberMapper;
import com.echomind.agent.team.store.TeamRunEntity;
import com.echomind.agent.team.store.TeamRunMapper;
import com.echomind.agent.team.store.TeamStepEntity;
import com.echomind.agent.team.store.TeamStepMapper;
import com.echomind.agent.team.visualization.MermaidGenerator;
import com.echomind.common.exception.ModelInvocationRejectedException;
import com.echomind.common.model.ErrorDetail;
import com.echomind.common.observability.EchoMindTrace;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent Team 的 MySQL 黑板和异步状态机服务。
 *
 * <p>这里保留 Team Run 的事务性编排：创建 Run、推进 DAG、写 Step/Event、生成快照。 Prompt 文本放在 {@link
 * TeamPromptFactory}，Executor 选择放在 {@link TeamAgentSelector}， 本类尽量只处理状态流转和持久化。
 */
@Slf4j
@Service
public class TeamBlackboardService {

  private static final String OP_PLANNER = "echomind.team.planner";
  private static final String OP_REVIEWER = "echomind.team.reviewer";
  private static final String OP_EXECUTOR = "echomind.team.executor";
  private static final String OP_SUB_REVIEWER = "echomind.team.sub_reviewer";
  private static final String OP_MERGE = "echomind.team.merge";
  private static final String OP_CONFLICT_DETECTOR = "echomind.team.conflict_detector";
  private static final String OP_ARBITRATION = "echomind.team.arbitration";
  private static final String OP_REPAIR = "echomind.team.repair";

  private enum ReviewerDecisionScope {
    PLAN,
    STEP,
    GLOBAL
  }

  private final TeamMapper teamMapper;
  private final TeamMemberMapper memberMapper;
  private final TeamRunMapper runMapper;
  private final TeamStepMapper stepMapper;
  private final TeamEventMapper eventMapper;
  private final AgentFactory agentFactory;
  private final AgentOrchestrator orchestrator;
  private final TeamStepCommandProducer teamStepCommandProducer;
  private final TeamRuntimeProperties runtimeProperties;
  private TeamUsageRecorder usageRecorder = TeamUsageRecorder.NOOP;

  private final TeamJsonSupport json = new TeamJsonSupport();
  private final TeamAgentSelector agentSelector = new TeamAgentSelector();
  private final TeamRiskPolicy riskPolicy = new TeamRiskPolicy();

  // 删除 Team/Run 后，后台异步任务可能仍在收尾；用它阻止继续写事件。
  private final Set<String> deletedRunIds = ConcurrentHashMap.newKeySet();

  public TeamBlackboardService(
      TeamMapper teamMapper,
      TeamMemberMapper memberMapper,
      TeamRunMapper runMapper,
      TeamStepMapper stepMapper,
      TeamEventMapper eventMapper,
      AgentFactory agentFactory,
      AgentOrchestrator orchestrator) {
    this(
        teamMapper,
        memberMapper,
        runMapper,
        stepMapper,
        eventMapper,
        agentFactory,
        orchestrator,
        null,
        new TeamRuntimeProperties());
  }

  @Autowired
  public TeamBlackboardService(
      TeamMapper teamMapper,
      TeamMemberMapper memberMapper,
      TeamRunMapper runMapper,
      TeamStepMapper stepMapper,
      TeamEventMapper eventMapper,
      AgentFactory agentFactory,
      AgentOrchestrator orchestrator,
      ObjectProvider<TeamStepCommandProducer> teamStepCommandProducer,
      TeamRuntimeProperties runtimeProperties) {
    this.teamMapper = teamMapper;
    this.memberMapper = memberMapper;
    this.runMapper = runMapper;
    this.stepMapper = stepMapper;
    this.eventMapper = eventMapper;
    this.agentFactory = agentFactory;
    this.orchestrator = orchestrator;
    this.teamStepCommandProducer =
        teamStepCommandProducer == null ? null : teamStepCommandProducer.getIfAvailable();
    this.runtimeProperties =
        runtimeProperties == null ? new TeamRuntimeProperties() : runtimeProperties;
  }

  @Autowired(required = false)
  void setUsageRecorder(TeamUsageRecorder usageRecorder) {
    this.usageRecorder = usageRecorder == null ? TeamUsageRecorder.NOOP : usageRecorder;
  }

  /** 查询用户的团队列表 */
  @Transactional(readOnly = true)
  public List<TeamSnapshot> listTeams(String userId) {
    return teamMapper.selectByOwnerUserIdOrderByCreatedAtAsc(userId).stream()
        .map(this::toTeamSnapshot)
        .toList();
  }

  /** 根据团队 ID 获取团队快照 */
  @Transactional(readOnly = true)
  public TeamSnapshot getTeam(String teamId) {
    return teamMapper
        .selectOptionalById(teamId)
        .map(this::toTeamSnapshot)
        .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
  }

  /**
   * 创建团队
   *
   * @param userId 用户 ID
   * @param requestedName 团队名称
   * @param specs 团队成员规格列表
   */
  @Transactional
  public TeamSnapshot createTeam(String userId, String requestedName, List<TeamMemberSpec> specs) {
    // 规范化成员规格
    List<TeamMemberSpec> normalized = normalizeSpecs(specs);
    // 验证成员配置
    validateMembers(normalized);

    // 创建团队实体
    TeamEntity team = new TeamEntity();
    team.setTeamId(UUID.randomUUID().toString());
    team.setOwnerUserId(userId);
    team.setName(
        requestedName == null || requestedName.isBlank()
            ? "Team-" + UUID.randomUUID().toString().substring(0, 8)
            : requestedName.trim());
    teamMapper.upsertById(team);

    // 创建团队成员实体
    for (TeamMemberSpec spec : normalized) {
      TeamMemberEntity member = new TeamMemberEntity();
      member.setTeamId(team.getTeamId());
      member.setAgentId(spec.agentId());
      member.setRole(spec.role());
      member.setCapabilityTagsJson(
          json.toJson(spec.capabilityTags() == null ? List.of() : spec.capabilityTags()));
      member.setSortOrder(spec.sortOrder());
      memberMapper.upsertById(member);
    }

    return getTeam(team.getTeamId());
  }

  /** 删除团队（级联删除相关的 Run、Step、Event） */
  @Transactional
  public void deleteTeam(String teamId, String userId) {
    if (teamId == null || teamId.isBlank()) {
      throw new IllegalArgumentException("teamId is required");
    }
    TeamEntity team = loadTeamForOwner(teamId, userId);
    // 获取所有关联的 Run ID
    List<String> runIds =
        runMapper.selectByTeamIdOrderByCreatedAtDesc(team.getTeamId()).stream()
            .map(TeamRunEntity::getRunId)
            .toList();
    // 添加到已删除集合（防止后台任务继续写事件）
    deletedRunIds.addAll(runIds);
    // 级联删除
    if (!runIds.isEmpty()) {
      eventMapper.deleteByRunIdIn(runIds);
      stepMapper.deleteByRunIdIn(runIds);
    }
    runMapper.deleteByTeamId(team.getTeamId());
    memberMapper.deleteByTeamId(team.getTeamId());
    teamMapper.deleteEntity(team);
  }

  /** 创建团队协作任务（简化版本） */
  @Transactional
  public TeamRunSnapshot createRun(String teamId, String task) {
    return createRun(teamId, "default", task, TeamReviewOptions.QUALITY_FIRST);
  }

  /** 创建团队协作任务（简化版本） */
  @Transactional
  public TeamRunSnapshot createRun(String teamId, String userId, String task) {
    return createRun(teamId, userId, task, TeamReviewOptions.QUALITY_FIRST);
  }

  /**
   * 创建团队协作任务（完整版本）
   *
   * <p>这是 Team 模块的核心入口方法，负责创建一个新的团队协作任务 Run。 创建后会通过事务同步机制在事务提交后异步触发执行流程。
   *
   * <p><strong>执行流程：</strong>
   *
   * <ol>
   *   <li>参数验证和规范化
   *   <li>验证用户对团队的访问权限
   *   <li>创建 TeamRunEntity 并持久化到 MySQL
   *   <li>记录 RUN_CREATED 事件
   *   <li>注册事务同步回调，事务提交后异步调度执行
   * </ol>
   *
   * @param teamId 团队 ID
   * @param userId 用户 ID
   * @param task 任务描述
   * @param reviewOptions 审查选项（控制各阶段审查是否启用）
   * @return 创建的 Run 快照
   */
  @Transactional
  public TeamRunSnapshot createRun(
      String teamId, String userId, String task, TeamReviewOptions reviewOptions) {
    if (task == null || task.isBlank()) {
      throw new IllegalArgumentException("task is required");
    }
    TeamReviewOptions normalizedOptions = TeamReviewOptions.normalize(reviewOptions);

    // 验证团队权限 - 确保用户有权访问该团队
    loadTeamForOwner(teamId, userId);

    // 创建 Run 实体
    TeamRunEntity run = new TeamRunEntity();
    run.setRunId(UUID.randomUUID().toString());
    run.setTeamId(teamId);
    run.setUserId(normalizeUserId(userId));
    run.setTask(task.trim());
    run.setPlanReviewEnabled(normalizedOptions.planReviewEnabled());
    run.setSubReviewEnabled(normalizedOptions.subReviewEnabled());
    run.setGlobalReviewEnabled(normalizedOptions.globalReviewEnabled());
    run.setSimpleFastPathEnabled(normalizedOptions.simpleFastPathEnabled());
    run.setStatus(TeamRunStatus.PENDING);
    runMapper.upsertById(run);

    // 记录创建事件
    recordEvent(run.getRunId(), null, TeamEventType.RUN_CREATED, null, null, "Run created", null);

    // 事务提交后异步调度执行 - 使用事务同步确保数据持久化后再触发
    scheduleRun(run.getRunId());

    return getRun(teamId, userId, run.getRunId());
  }

  /** 查询团队的 Run 列表 */
  @Transactional(readOnly = true)
  public List<TeamRunSnapshot> listRuns(String teamId) {
    return listRuns(teamId, "default");
  }

  /** 查询团队的 Run 列表（带用户隔离） */
  @Transactional(readOnly = true)
  public List<TeamRunSnapshot> listRuns(String teamId, String userId) {
    loadTeamForOwner(teamId, userId);
    return runMapper
        .selectByTeamIdAndUserIdOrderByCreatedAtDesc(teamId, normalizeUserId(userId))
        .stream()
        .map(run -> toRunSnapshot(run, true, false))
        .toList();
  }

  /** 查询用户的所有 Run */
  @Transactional(readOnly = true)
  public List<TeamRunSnapshot> listRunsForUser(String userId) {
    return runMapper.selectByUserIdOrderByCreatedAtDesc(normalizeUserId(userId)).stream()
        .map(run -> toRunSnapshot(run, true, false))
        .toList();
  }

  /** 获取单个 Run 快照 */
  @Transactional(readOnly = true)
  public TeamRunSnapshot getRun(String teamId, String runId) {
    return getRun(teamId, "default", runId);
  }

  /** 获取单个 Run 快照（带用户隔离） */
  @Transactional(readOnly = true)
  public TeamRunSnapshot getRun(String teamId, String userId, String runId) {
    TeamRunEntity run = loadRun(teamId, normalizeUserId(userId), runId);
    return toRunSnapshot(run, true, true);
  }

  @Transactional
  public TeamRunSnapshot repairRunDag(String teamId, String userId, String runId) {
    TeamRunEntity run = loadRun(teamId, normalizeUserId(userId), runId);
    if (isTerminal(run.getStatus())) {
      return toRunSnapshot(run, true, true);
    }
    if (dagStoreInstance == null || teamStepCommandProducer == null) {
      throw new IllegalStateException("Team DAG runtime is not available");
    }
    List<TeamStepEntity> steps = stepMapper.selectByRunIdOrderByStepIndexAsc(runId);
    boolean hasActiveStep =
        steps.stream()
            .anyMatch(
                step ->
                    step.getStatus() == TeamStepStatus.ASSIGNED
                        || step.getStatus() == TeamStepStatus.RUNNING
                        || step.getStatus() == TeamStepStatus.RETRYING);
    if (hasActiveStep) {
      throw new IllegalStateException("Run has active steps and cannot be repaired safely");
    }
    repairStepStatesFromMysql(runId, steps);
    steps = stepMapper.selectByRunIdOrderByStepIndexAsc(runId);
    markRunExecutingFromCoordinator(run);
    dagStoreInstance.destroyDag(runId);
    dagStoreInstance.initializeDag(runId, steps, runtimeProperties.getMaxConcurrentSteps());
    dagStoreInstance.setDagStatus(runId, "EXECUTING");
    scheduleReadyStepDispatch(runId);
    recordEvent(
        runId,
        null,
        TeamEventType.TEAM_CONTROL_STARTED,
        null,
        null,
        "DAG runtime repaired and ready steps scheduled",
        Map.of("repair", true));
    return toRunSnapshot(
        runMapper
            .selectOptionalById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId)),
        true,
        true);
  }

  /** 清除结果制品（用于重新规划） */
  private void clearResultArtifacts(TeamRunEntity run) {
    run.setResultReviewJson(null);
    run.setGlobalReviewJson(null);
    run.setMergeOutput(null);
    run.setConflictReportJson(null);
    run.setArbitrationJson(null);
    run.setFinalOutput(null);
    run.setMermaidDiagram(null);
  }

  /** 规划和审查（简化版本） */
  private void planAndReview(TeamRunEntity run, TeamSnapshot team) {
    planAndReview(run, team, List.of(), "", List.of());
  }

  /** 规划和审查（完整版本） */
  private void planAndReview(
      TeamRunEntity run,
      TeamSnapshot team,
      List<PlannedStep> initialPreviousPlan,
      String initialRevisionInstructions,
      List<TeamStepSnapshot> previousExecutionResults) {
    planAndReview(
        run,
        team,
        initialPreviousPlan,
        initialRevisionInstructions,
        previousExecutionResults,
        false);
  }

  /**
   * 规划和审查的核心逻辑
   *
   * <p><strong>核心设计思想：</strong>实现 Planner 和 Reviewer 的闭环反馈机制。 Reviewer 不通过时，将上一轮计划和修改意见带回
   * Planner，形成迭代优化循环。
   *
   * <p><strong>执行流程：</strong>
   *
   * <ol>
   *   <li><strong>规划阶段</strong>：调用 Planner Agent 将任务分解为步骤计划
   *   <li><strong>快路径检查</strong>：如果是简单任务且仅单步，直接走快路径执行
   *   <li><strong>审查阶段</strong>：调用 Reviewer Agent 审核计划合理性
   *   <li><strong>决策处理</strong>：根据审查结果决定继续/重试/失败
   * </ol>
   *
   * <p><strong>重试机制：</strong>
   *
   * <ul>
   *   <li>Reviewer 返回 RETRY 时，将修改指令传递给 Planner 重新规划
   *   <li>重试次数受 maxPlanRetries 配置限制
   *   <li>达到重试上限时任务失败
   * </ul>
   *
   * @param run Run 实体
   * @param team 团队快照
   * @param initialPreviousPlan 初始前一轮计划（用于重试场景）
   * @param initialRevisionInstructions 初始修改指令（Reviewer 给出的修改建议）
   * @param previousExecutionResults 前一轮执行结果（用于修复场景）
   * @param preserveExistingSteps 是否保留现有步骤（用于修复时不清除已有步骤）
   */
  private void planAndReview(
      TeamRunEntity run,
      TeamSnapshot team,
      List<PlannedStep> initialPreviousPlan,
      String initialRevisionInstructions,
      List<TeamStepSnapshot> previousExecutionResults,
      boolean preserveExistingSteps) {
    // 标记计划是否通过审核
    boolean approved = false;
    // 初始化计划（用于重试时传递上一轮计划）
    List<PlannedStep> previousPlan = initialPreviousPlan == null ? List.of() : initialPreviousPlan;
    // 初始化 Planner 的修改指令
    String plannerRevisionInstructions = nullToBlank(initialRevisionInstructions);

    // 循环直到计划通过审核或达到最大重试次数
    while (!approved && run.getPlanRetryCount() <= runtimeProperties.getMaxPlanRetries()) {
      // 1. 设置状态为 PLANNING，开始规划阶段
      run.setStatus(TeamRunStatus.PLANNING);
      runMapper.upsertById(run);
      recordEvent(
          run.getRunId(),
          null,
          TeamEventType.PLAN_STARTED,
          TeamRole.PLANNER,
          planner(team).agentId(),
          "Planner started task decomposition",
          null);

      // 2. 调用 Planner Agent 生成步骤计划
      List<PlannedStep> planned =
          callPlanner(
              run, team, previousPlan, plannerRevisionInstructions, previousExecutionResults);
      requireRunWritable(run.getRunId());

      // 3. 用新计划替换现有步骤
      replaceSteps(run, planned, preserveExistingSteps);
      recordEvent(
          run.getRunId(),
          null,
          TeamEventType.PLAN_CREATED,
          TeamRole.PLANNER,
          planner(team).agentId(),
          "Planner created " + planned.size() + " step(s)",
          planned);

      // 检查是否使用简单快路径 - SIMPLE 任务类型且单步骤时跳过审查直接执行
      if (shouldUseSimpleFastPath(run)) {
        executeSimpleFastPath(run, team);
        return;
      }

      // 如果跳过计划审查 - 由用户配置决定
      if (!reviewOptions(run).planReviewEnabled()) {
        ReviewerDecision skipped = skippedReviewDecision("用户跳过 PlanReview，使用 Planner 计划继续执行", "");
        run.setPlanReviewJson(json.toJson(skipped));
        runMapper.upsertById(run);
        recordEvent(
            run.getRunId(),
            null,
            TeamEventType.PLAN_REVIEWED,
            TeamRole.REVIEWER,
            reviewer(team).agentId(),
            skipped.reason(),
            skipped);
        approved = true;
        continue;
      }

      // 4. 设置状态为 PLAN_REVIEWING，开始审核阶段
      run.setStatus(TeamRunStatus.PLAN_REVIEWING);
      runMapper.upsertById(run);
      recordEvent(
          run.getRunId(),
          null,
          TeamEventType.PLAN_REVIEW_STARTED,
          TeamRole.REVIEWER,
          reviewer(team).agentId(),
          "Reviewer started plan review",
          null);

      // 5. 调用 Reviewer Agent 审核计划
      ReviewerDecision decision = callReviewerForPlan(run, team, planned);
      requireRunWritable(run.getRunId());
      run.setPlanReviewJson(json.toJson(decision));
      runMapper.upsertById(run);
      recordEvent(
          run.getRunId(),
          null,
          TeamEventType.PLAN_REVIEWED,
          TeamRole.REVIEWER,
          reviewer(team).agentId(),
          decision.reason(),
          decision);

      // 6. 根据审核结果处理
      if (decision.action() == ReviewerAction.CONTINUE) {
        // 计划通过审核，退出循环进入执行阶段
        approved = true;
      } else if (run.getPlanRetryCount() >= runtimeProperties.getMaxPlanRetries()) {
        // 达到最大重试次数，任务失败
        failRun(run.getRunId(), "Planner retry limit reached: " + decision.reason());
        return;
      } else {
        // 计划需要修改，准备下一轮规划迭代
        previousPlan = planned;
        plannerRevisionInstructions = decision.revisionInstructions();
        run.setPlanRetryCount(run.getPlanRetryCount() + 1);
        runMapper.upsertById(run);
        recordEvent(
            run.getRunId(),
            null,
            TeamEventType.RETRY_REQUESTED,
            TeamRole.REVIEWER,
            reviewer(team).agentId(),
            "Reviewer requested planner retry: " + decision.reason(),
            decision);
      }
    }
  }

  /** 调用 Planner Agent 生成步骤计划 */
  private List<PlannedStep> callPlanner(
      TeamRunEntity run,
      TeamSnapshot team,
      List<PlannedStep> previousPlan,
      String plannerRevisionInstructions,
      List<TeamStepSnapshot> previousExecutionResults) {
    TeamMember planner = planner(team);
    String prompt =
        TeamPromptFactory.planner(
            run, team, previousPlan, plannerRevisionInstructions, previousExecutionResults, json);
    PipelineContext result =
        executeTeamControlInternal(run, OP_PLANNER, planner.agentId(), plannerSession(run), prompt);
    String raw = result.getFinalResponse();
    List<PlannedStep> steps = json.parsePlan(raw);
    if (steps.isEmpty()) {
      throw new IllegalStateException("Planner did not produce any steps");
    }
    run.setTaskLevel(json.parseTaskLevel(raw, steps.size()));
    runMapper.upsertById(run);
    return steps;
  }

  /** 调用 Reviewer Agent 审核计划 */
  private ReviewerDecision callReviewerForPlan(
      TeamRunEntity run, TeamSnapshot team, List<PlannedStep> planned) {
    TeamMember reviewer = reviewer(team);
    return executeReviewerDecision(
        reviewer,
        run,
        TeamPromptFactory.planReviewer(run, planned, json),
        ReviewerDecisionScope.PLAN);
  }

  /** 判断是否使用简单快路径 */
  private boolean shouldUseSimpleFastPath(TeamRunEntity run) {
    if (!reviewOptions(run).simpleFastPathEnabled() || run.getTaskLevel() != TeamTaskLevel.SIMPLE) {
      return false;
    }
    return activeSteps(run.getRunId()).size() == 1;
  }

  /** 获取活跃步骤列表（排除 SUPERSEDED） */
  private List<TeamStepEntity> activeSteps(String runId) {
    return stepMapper.selectByRunIdOrderByStepIndexAsc(runId).stream()
        .filter(step -> step.getStatus() != TeamStepStatus.SUPERSEDED)
        .toList();
  }

  /** 执行简单快路径（单步骤直接执行） */
  private void executeSimpleFastPath(TeamRunEntity run, TeamSnapshot team) {
    List<TeamStepEntity> steps = activeSteps(run.getRunId());
    if (steps.size() != 1) {
      return;
    }
    TeamStepEntity step = steps.get(0);
    run.setStatus(TeamRunStatus.EXECUTING);
    runMapper.upsertById(run);
    recordEvent(
        run.getRunId(),
        null,
        TeamEventType.TEAM_CONTROL_STARTED,
        null,
        null,
        "SIMPLE 快路径启动：单 Executor 执行后直接返回结果",
        Map.of("simpleFastPath", true, "reviewOptions", reviewOptions(run)));

    // 选择 Executor
    List<String> requiredCapabilities = json.stringList(step.getRequiredCapabilitiesJson());
    TeamAgentSelection selection =
        agentSelector.selectExecutor(team.members(), requiredCapabilities);
    TeamMember executor = executorBySelection(team, selection);
    if (executor == null) {
      throw new IllegalStateException("No executor configured");
    }

    // 设置步骤状态并执行
    step.setAssignedAgentId(executor.agentId());
    step.setStatus(TeamStepStatus.ASSIGNED);
    step.setStartedAt(Instant.now());
    stepMapper.upsertById(step);

    step.setStatus(TeamStepStatus.RUNNING);
    stepMapper.upsertById(step);

    // 执行步骤
    String prompt = TeamPromptFactory.executor(run, step, "");
    PipelineContext result =
        executeTeamInternal(run, OP_EXECUTOR, executor.agentId(), stepSession(run, step), prompt);
    String output = result == null ? null : result.getFinalResponse();

    // 完成步骤和 Run
    requireRunWritable(run.getRunId());
    step.setRawOutput(output);
    if (!completeStep(
        run.getRunId(), step, executor.agentId(), false, TeamStepQualityStatus.PASSED)) {
      return;
    }
    completeRunWithFinalOutput(
        run,
        team,
        nullToBlank(output),
        TeamRole.EXECUTOR,
        executor.agentId(),
        "SIMPLE 快路径完成，直接返回 Executor 输出");
  }

  /**
   * 执行单个 Step 的完整生命周期。
   *
   * <p>这是 Team 任务执行的核心方法，负责协调单个步骤的完整执行流程：
   *
   * <ol>
   *   <li><strong>加载实体</strong>：从数据库获取 Run、Team、Step 实体
   *   <li><strong>状态管理</strong>：先更新步骤状态为 RUNNING，确保前端可见
   *   <li><strong>选择 Executor</strong>：基于能力匹配 + 模型智能选择合适的执行 Agent
   *   <li><strong>构建提示词</strong>：生成包含依赖步骤输出的执行提示词
   *   <li><strong>执行 Agent</strong>：调用选定的 Executor Agent 执行步骤
   *   <li><strong>结果处理</strong>：保存执行结果，并按 Run 配置执行每步 Review
   * </ol>
   *
   * <p><strong>状态流转：</strong> <br>
   * READY/RETRYING → RUNNING → COMPLETED/FAILED，Executor 选择完成后补充 assignedAgentId。
   *
   * @param runId 任务运行 ID
   * @param stepId 步骤 ID
   */
  @Transactional
  protected void executeStep(String runId, String stepId) {
    // ============================================
    // 阶段1：加载实体
    // ============================================
    TeamRunEntity run =
        runMapper
            .selectOptionalById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));

    TeamSnapshot team = getTeam(run.getTeamId());

    TeamStepEntity step =
        stepMapper
            .selectOptionalById(stepId)
            .orElseThrow(() -> new IllegalArgumentException("Step not found: " + stepId));

    if (!isStepExecutable(step.getStatus())) {
      log.info("Skip step {} for run {} because status is {}", stepId, runId, step.getStatus());
      return;
    }

    // ============================================
    // 阶段2：立即标记步骤开始，避免 AgentSelector LLM 慢时前端仍显示待调度
    // ============================================
    boolean retrying = step.getRetryCount() > 0 || step.getStatus() == TeamStepStatus.RETRYING;
    markStepExecutionAccepted(runId, stepId);

    step =
        stepMapper
            .selectOptionalById(stepId)
            .orElseThrow(() -> new IllegalArgumentException("Step not found: " + stepId));

    // ============================================
    // 阶段3：选择 Executor Agent
    // ============================================
    List<String> requiredCapabilities = json.stringList(step.getRequiredCapabilitiesJson());
    TeamAgentSelection selection = selectExecutorWithModel(run, team, step, requiredCapabilities);
    TeamMember executor = selection == null ? null : executorBySelection(team, selection);

    if (executor == null) {
      throw new IllegalStateException("No executor configured");
    }

    recordEvent(
        runId,
        stepId,
        TeamEventType.AGENT_SELECTED,
        TeamRole.PLANNER,
        planner(team).agentId(),
        "模型选择 Executor "
            + executor.agentName()
            + "："
            + (selection != null ? selection.reason() : "规则兜底"),
        selection);

    step.setAssignedAgentId(executor.agentId());
    step.setSubReviewJson(null);
    stepMapper.upsertById(step);

    recordEvent(
        runId,
        stepId,
        TeamEventType.STEP_ASSIGNED,
        TeamRole.EXECUTOR,
        executor.agentId(),
        "Step assigned to " + executor.agentName(),
        Map.of(
            "requiredCapabilities", requiredCapabilities,
            "selection", selection));

    // ============================================
    // 阶段5：构建执行提示词
    // ============================================
    String prompt = TeamPromptFactory.executor(run, step, dependencyOutputs(step));
    step.setInputJson(json.toJson(Map.of("prompt", prompt, "selection", selection)));
    stepMapper.upsertById(step);

    // ============================================
    // 阶段6：调用 Agent 执行
    // ============================================
    String output;
    try {
      PipelineContext result =
          executeTeamInternal(run, OP_EXECUTOR, executor.agentId(), stepSession(run, step), prompt);
      output = result != null ? result.getFinalResponse() : null;

      if (output != null && output.startsWith("[Error]")) {
        if (isToolBudgetLimit(output)) {
          output = executorToolBudgetFallback(run, step, executor, output);
          requireRunWritable(runId);
          if (isStepTerminalNow(stepId)) {
            return;
          }
          step.setRawOutput(output);
          completeStep(
              runId, step, executor.agentId(), retrying, TeamStepQualityStatus.FLAWED_ACCEPTED);
          return;
        }
        throw new IllegalStateException(output);
      }
    } catch (Exception e) {
      if (isStepTerminalNow(stepId)) {
        log.info("Skip failing step {} for run {} because it is already terminal", stepId, runId);
        return;
      }
      step.setStatus(TeamStepStatus.FAILED);
      step.setRawOutput("Step failed: " + e.getMessage());
      stepMapper.upsertById(step);
      throw e;
    }

    // ============================================
    // 阶段7：保存执行结果
    // ============================================
    requireRunWritable(runId);
    if (isStepTerminalNow(stepId)) {
      log.info("Skip writing output for step {} because it is already terminal", stepId);
      return;
    }

    step.setRawOutput(output);

    // ============================================
    // 阶段8：处理结果（可选每步 Review）
    // ============================================
    reviewStepOutput(run, team, step, retrying);
  }

  /** 工具调用预算耗尽时的降级处理 */
  private String executorToolBudgetFallback(
      TeamRunEntity run, TeamStepEntity step, TeamMember executor, String failureReason) {
    String prompt =
        TeamPromptFactory.executorToolBudgetFallback(
            run, step, dependencyOutputs(step), failureReason);
    PipelineContext fallback =
        executeTeamControlInternal(
            run,
            OP_EXECUTOR,
            executor.agentId(),
            stepSession(run, step) + "-tool-budget-fallback-" + UUID.randomUUID(),
            prompt);
    String summary = fallback == null ? "" : fallback.getFinalResponse();
    if (summary == null || summary.isBlank() || summary.startsWith("[Error]")) {
      throw new IllegalStateException(
          "Executor tool budget fallback failed: " + nullToBlank(summary));
    }
    return "工具调用达到上限，已降级总结：\n\n" + summary;
  }

  /** 判断是否为工具调用预算限制错误 */
  private boolean isToolBudgetLimit(String output) {
    return output != null && output.contains("工具调用次数超过上限");
  }

  /**
   * 选择执行步骤的 Executor Agent。
   *
   * <p>采用"模型主选 + 规则兜底"策略：
   *
   * <ol>
   *   <li>首先通过规则引擎获取候选 Executor 列表（按能力匹配度排序）
   *   <li>调用 Planner 模型进行智能选择（考虑步骤语义、依赖输出等）
   *   <li>模型选择有效时返回模型选择结果
   *   <li>模型选择无效或异常时，使用规则兜底（选择候选列表首位）
   * </ol>
   */
  private TeamAgentSelection selectExecutorWithModel(
      TeamRunEntity run,
      TeamSnapshot team,
      TeamStepEntity step,
      List<String> requiredCapabilities) {
    // 1. 获取候选 Executor 列表
    List<TeamAgentSelection> candidates =
        agentSelector.rankExecutors(team.members(), requiredCapabilities);
    if (candidates.isEmpty()) {
      return null;
    }

    // 2. 创建规则兜底选择
    TeamAgentSelection fallback =
        candidates
            .get(0)
            .withDecision("RULE_FALLBACK", "模型选择不可用时的规则兜底：" + candidates.get(0).reason());

    // 3. 调用 Planner 模型进行智能选择
    TeamMember planner = planner(team);
    String prompt =
        TeamPromptFactory.agentSelector(
            run, step, requiredCapabilities, dependencyOutputs(step), candidates, json);

    try {
      PipelineContext result =
          executeTeamControlInternal(
              run,
              OP_PLANNER,
              planner.agentId(),
              "team-run-"
                  + run.getRunId()
                  + "-agent-selector-"
                  + step.getStepId()
                  + "-"
                  + UUID.randomUUID(),
              prompt);

      TeamExecutorSelectionDecision decision =
          json.parseExecutorSelectionDecision(result == null ? "" : result.getFinalResponse());
      TeamAgentSelection selected =
          agentSelector.matchModelDecision(candidates, decision.memberKey(), decision.agentId());

      if (selected != null) {
        return selected.withDecision(
            "MODEL",
            "模型自主选择："
                + blankToDefault(decision.reason(), "匹配当前 Step 的语义和能力要求")
                + "；"
                + selected.reason());
      }
      return fallback.withDecision(
          "RULE_FALLBACK",
          "模型返回的 Executor 不在候选列表中，使用规则兜底；模型原始选择 memberKey="
              + decision.memberKey()
              + "，agentId="
              + decision.agentId()
              + "；"
              + fallback.reason());
    } catch (Exception e) {
      log.warn(
          "AgentSelector model decision failed for run {} step {}: {}",
          run.getRunId(),
          step.getStepId(),
          e.getMessage());
      return fallback.withDecision(
          "RULE_FALLBACK", "模型选择失败，使用规则兜底：" + e.getMessage() + "；" + fallback.reason());
    }
  }

  /** 审查步骤输出（每步 Review） */
  private void reviewStepOutput(
      TeamRunEntity run, TeamSnapshot team, TeamStepEntity step, boolean retrying) {
    if (isStepTerminalNow(step.getStepId())) {
      log.info(
          "Skip reviewing step {} for run {} because it is already terminal",
          step.getStepId(),
          run.getRunId());
      return;
    }

    // 如果跳过每步审查
    if (!reviewOptions(run).subReviewEnabled()) {
      ReviewerDecision skipped = skippedReviewDecision("用户跳过每步 Review，Step 输出直接放行", "");
      step.setSubReviewJson(json.toJson(skipped));
      step.setLastReviewReason(skipped.reason());
      recordEvent(
          run.getRunId(),
          step.getStepId(),
          TeamEventType.STEP_SUB_REVIEWED,
          TeamRole.SUB_REVIEWER,
          subReviewer(team).agentId(),
          skipped.reason(),
          skipped);
      completeStep(
          run.getRunId(),
          step,
          step.getAssignedAgentId(),
          retrying,
          TeamStepQualityStatus.REVIEW_SKIPPED);
      return;
    }

    // 调用 StepReviewer 审查
    TeamMember reviewer = subReviewer(team);
    recordEvent(
        run.getRunId(),
        step.getStepId(),
        TeamEventType.STEP_SUB_REVIEW_STARTED,
        TeamRole.SUB_REVIEWER,
        reviewer.agentId(),
        "StepReviewer 开始校验 Step 输出",
        Map.of("riskLevel", step.getRiskLevel() == null ? "" : step.getRiskLevel().name()));

    ReviewerDecision decision = callStepReviewer(run, reviewer, step);
    if (isStepTerminalNow(step.getStepId())) {
      return;
    }

    step.setSubReviewJson(json.toJson(decision));
    step.setLastReviewReason(decision.reason());
    recordEvent(
        run.getRunId(),
        step.getStepId(),
        TeamEventType.STEP_SUB_REVIEWED,
        TeamRole.SUB_REVIEWER,
        reviewer.agentId(),
        decision.reason(),
        decision);

    // 根据审查结果处理
    if (isStepPass(decision)) {
      completeStep(
          run.getRunId(), step, step.getAssignedAgentId(), retrying, TeamStepQualityStatus.PASSED);
      return;
    }
    if (decision.action() == ReviewerAction.ACCEPT_WITH_RISK) {
      step.setReflectionJson(json.toJson(reflectionForStep(step, decision)));
      completeStep(
          run.getRunId(),
          step,
          step.getAssignedAgentId(),
          retrying,
          TeamStepQualityStatus.FLAWED_ACCEPTED);
      return;
    }
    if (decision.action() == ReviewerAction.FAILED) {
      step.setQualityStatus(TeamStepQualityStatus.FAILED);
      step.setReviewStatus("FAILED");
      step.setStatus(TeamStepStatus.FAILED);
      step.setCompletedAt(Instant.now());
      stepMapper.upsertById(step);
      recordEvent(
          run.getRunId(),
          step.getStepId(),
          TeamEventType.STEP_FAILED,
          TeamRole.SUB_REVIEWER,
          reviewer.agentId(),
          "StepReviewer 判定 Step 失败: " + decision.reason(),
          decision);
      failRun(run.getRunId(), "StepReviewer 判定 Step 失败: " + decision.reason());
      return;
    }

    // 重试处理
    if (step.getRetryCount() >= runtimeProperties.getMaxStepRetries()) {
      failRun(
          run.getRunId(),
          "StepReview rework limit reached for step "
              + step.getStepId()
              + ": "
              + decision.reason());
      return;
    }
    prepareStepRetry(run, step, decision, reviewer);
  }

  /** 调用 StepReviewer */
  private ReviewerDecision callStepReviewer(
      TeamRunEntity run, TeamMember reviewer, TeamStepEntity step) {
    return executeReviewerDecision(
        reviewer, run, TeamPromptFactory.subReviewer(run, step), ReviewerDecisionScope.STEP);
  }

  /** 完成步骤 */
  private boolean completeStep(
      String runId,
      TeamStepEntity step,
      String actorAgentId,
      boolean retrying,
      TeamStepQualityStatus qualityStatus) {
    if (isRunDeleted(runId)) {
      return false;
    }
    if (isStepTerminalNow(step.getStepId())) {
      log.info(
          "Skip completing step {} for run {} because it is already terminal",
          step.getStepId(),
          runId);
      return false;
    }
    step.setQualityStatus(qualityStatus);
    step.setReviewStatus(qualityStatus.name());
    step.setStatus(TeamStepStatus.COMPLETED);
    step.setCompletedAt(Instant.now());
    stepMapper.upsertById(step);
    recordEvent(
        runId,
        step.getStepId(),
        retrying ? TeamEventType.STEP_RETRY_COMPLETED : TeamEventType.STEP_COMPLETED,
        TeamRole.EXECUTOR,
        actorAgentId,
        "Executor 完成 Step",
        Map.of("qualityStatus", qualityStatus.name()));
    return true;
  }

  /** 判断步骤是否可执行 */
  private boolean isStepExecutable(TeamStepStatus status) {
    return status == TeamStepStatus.READY
        || status == TeamStepStatus.RETRYING
        || status == TeamStepStatus.RUNNING;
  }

  @Transactional
  public void markStepExecutionAccepted(String runId, String stepId) {
    if (isRunDeleted(runId)) {
      return;
    }
    TeamStepEntity step = stepMapper.selectOptionalById(stepId).orElse(null);
    if (step == null || isStepTerminal(step.getStatus())) {
      return;
    }
    if (step.getStatus() != TeamStepStatus.READY
        && step.getStatus() != TeamStepStatus.RETRYING
        && step.getStatus() != TeamStepStatus.RUNNING) {
      return;
    }
    boolean alreadyRunning = step.getStatus() == TeamStepStatus.RUNNING;
    boolean retrying = step.getRetryCount() > 0 || step.getStatus() == TeamStepStatus.RETRYING;
    if (!alreadyRunning) {
      step.setStatus(TeamStepStatus.RUNNING);
      step.setStartedAt(Instant.now());
      stepMapper.upsertById(step);
      recordEvent(
          runId,
          stepId,
          retrying ? TeamEventType.STEP_RETRY_STARTED : TeamEventType.STEP_STARTED,
          TeamRole.EXECUTOR,
          step.getAssignedAgentId(),
          retrying ? "Executor retry started step" : "Executor started step",
          Map.of("phase", "accepted"));
    } else if (step.getStartedAt() == null) {
      step.setStartedAt(Instant.now());
      stepMapper.upsertById(step);
    }
  }

  /** 判断步骤当前是否为终态 */
  private boolean isStepTerminalNow(String stepId) {
    return stepMapper
        .selectOptionalById(stepId)
        .map(TeamStepEntity::getStatus)
        .map(this::isStepTerminal)
        .orElse(true);
  }

  /** 判断步骤状态是否为终态 */
  private boolean isStepTerminal(TeamStepStatus status) {
    return status == TeamStepStatus.COMPLETED
        || status == TeamStepStatus.FAILED
        || status == TeamStepStatus.SUPERSEDED;
  }

  /**
   * 合并步骤结果并执行全局审查
   *
   * <p><strong>核心设计思想：</strong>实现完整的结果聚合和审查流程，确保多步骤协作的最终输出质量。
   *
   * <p><strong>固定结果链路：</strong>
   *
   * <ol>
   *   <li><strong>合并阶段</strong>：MergeAgent 聚合所有步骤输出为统一报告
   *   <li><strong>冲突检测</strong>：ConflictDetector 检查步骤间的输出冲突
   *   <li><strong>冲突仲裁</strong>：如有冲突，Planner 给出仲裁决策
   *   <li><strong>二次聚合</strong>：带仲裁结果重新聚合（如有仲裁）
   *   <li><strong>全局审查</strong>：GlobalReviewer 对最终结果进行终审
   * </ol>
   *
   * <p><strong>冲突处理机制：</strong>
   *
   * <ul>
   *   <li>冲突检测由 ConflictDetector 完成，识别步骤输出间的矛盾和不一致
   *   <li>仲裁由 Planner 完成，给出解决冲突的指导意见
   *   <li>仲裁后重新聚合，确保最终结果一致
   *   <li>仲裁次数受 maxArbitrations 配置限制
   * </ul>
   *
   * @param run Run 实体
   * @param team 团队快照
   */
  private void mergeAndReviewResults(TeamRunEntity run, TeamSnapshot team) {
    run.setStatus(TeamRunStatus.MERGING);
    runMapper.upsertById(run);

    // 1. MergeAgent 聚合所有步骤输出
    TeamMember merger = merger(team);
    recordEvent(
        run.getRunId(),
        null,
        TeamEventType.MERGE_STARTED,
        TeamRole.MERGER,
        merger.agentId(),
        "MergeAgent 开始聚合 Step 输出",
        null);
    String mergeOutput = callMerger(run, merger);
    run.setMergeOutput(mergeOutput);
    runMapper.upsertById(run);
    recordEvent(
        run.getRunId(),
        null,
        TeamEventType.MERGE_COMPLETED,
        TeamRole.MERGER,
        merger.agentId(),
        "MergeAgent 完成聚合",
        Map.of("mergeOutput", mergeOutput));

    // 2. ConflictDetector 检测冲突 - 识别步骤输出间的矛盾和不一致
    TeamConflictReport conflictReport = detectConflicts(run, team, mergeOutput);
    run.setConflictReportJson(json.toJson(conflictReport));
    runMapper.upsertById(run);
    recordEvent(
        run.getRunId(),
        null,
        TeamEventType.CONFLICT_DETECTED,
        null,
        null,
        conflictReport.hasConflict() ? "ConflictDetector 发现冲突" : "ConflictDetector 未发现冲突",
        conflictReport);

    // 3. 如果有冲突且未达到最大仲裁次数，执行仲裁
    if (conflictReport.hasConflict()
        && run.getArbitrationCount() < runtimeProperties.getMaxArbitrations()) {
      String arbitration = arbitrateConflicts(run, team, conflictReport);
      run.setArbitrationCount(run.getArbitrationCount() + 1);
      run.setArbitrationJson(
          json.toJson(
              Map.of(
                  "arbitration", arbitration,
                  "conflictReport", conflictReport,
                  "arbitrationCount", run.getArbitrationCount())));
      runMapper.upsertById(run);

      // 带仲裁结果重新聚合
      recordEvent(
          run.getRunId(),
          null,
          TeamEventType.MERGE_STARTED,
          TeamRole.MERGER,
          merger.agentId(),
          "MergeAgent 带 Planner 仲裁结果二次聚合",
          null);
      mergeOutput = callMerger(run, merger);
      run.setMergeOutput(mergeOutput);
      TeamConflictReport secondReport = detectConflicts(run, team, mergeOutput);
      run.setConflictReportJson(json.toJson(secondReport));
      runMapper.upsertById(run);
    }

    // 4. 执行全局审查或直接完成
    if (!reviewOptions(run).globalReviewEnabled()) {
      ReviewerDecision skipped =
          skippedReviewDecision("用户跳过 GlobalReview，直接使用 MergeAgent 输出作为最终结果", mergeOutput);
      run.setResultReviewJson(json.toJson(skipped));
      run.setGlobalReviewJson(json.toJson(skipped));
      runMapper.upsertById(run);
      recordEvent(
          run.getRunId(),
          null,
          TeamEventType.GLOBAL_REVIEWED,
          TeamRole.REVIEWER,
          reviewer(team).agentId(),
          skipped.reason(),
          skipped);
      completeRunWithFinalOutput(
          run,
          team,
          mergeOutput,
          TeamRole.MERGER,
          merger.agentId(),
          "GlobalReview 已跳过，Run 使用 MergeAgent 输出完成");
      return;
    }

    // 执行全局审查
    reviewResults(run, team);
  }

  /** 调用 Merger Agent */
  private String callMerger(TeamRunEntity run, TeamMember merger) {
    return callMerger(run, merger, "");
  }

  /** 调用 Merger Agent（带合并指令） */
  private String callMerger(TeamRunEntity run, TeamMember merger, String mergeInstructions) {
    List<TeamStepSnapshot> steps =
        stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
            .map(this::toStepSnapshot)
            .toList();
    String prompt = TeamPromptFactory.merger(run, steps, json, mergeInstructions);
    PipelineContext result =
        executeTeamControlInternal(
            run,
            OP_MERGE,
            merger.agentId(),
            "team-run-" + run.getRunId() + "-merger-" + UUID.randomUUID(),
            prompt);
    String output = result == null ? "" : result.getFinalResponse();
    return output == null || output.isBlank() ? synthesizeFallbackReport(run) : output;
  }

  /** 检测步骤输出中的冲突 */
  private TeamConflictReport detectConflicts(
      TeamRunEntity run, TeamSnapshot team, String mergeOutput) {
    TeamMember reviewer = reviewer(team);
    List<TeamStepSnapshot> steps =
        stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
            .map(this::toStepSnapshot)
            .toList();
    String prompt = TeamPromptFactory.conflictDetector(run, steps, mergeOutput, json);
    try {
      PipelineContext result =
          executeTeamControlInternal(
              run,
              OP_CONFLICT_DETECTOR,
              reviewer.agentId(),
              "team-run-" + run.getRunId() + "-conflict-detector-" + UUID.randomUUID(),
              prompt);
      TeamConflictReport report =
          json.parseConflictReport(result == null ? "" : result.getFinalResponse());
      if (!report.hasConflict()) {
        return TeamConflictReport.none(blankToDefault(report.reason(), "未发现明显冲突"));
      }
      return report;
    } catch (Exception e) {
      log.warn("ConflictDetector failed for run {}: {}", run.getRunId(), e.getMessage());
      return TeamConflictReport.none("ConflictDetector 未能解析结构化结果，交由 GlobalReviewer 兜底终审");
    }
  }

  /** 执行冲突仲裁 */
  private String arbitrateConflicts(
      TeamRunEntity run, TeamSnapshot team, TeamConflictReport conflictReport) {
    TeamMember planner = planner(team);
    recordEvent(
        run.getRunId(),
        null,
        TeamEventType.ARBITRATION_STARTED,
        TeamRole.PLANNER,
        planner.agentId(),
        "Planner 开始冲突仲裁",
        conflictReport);
    List<TeamStepSnapshot> steps =
        stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
            .map(this::toStepSnapshot)
            .toList();
    String prompt = TeamPromptFactory.arbitration(run, conflictReport, steps, json);
    PipelineContext result =
        executeTeamControlInternal(
            run,
            OP_ARBITRATION,
            planner.agentId(),
            "team-run-" + run.getRunId() + "-planner-arbitration-" + UUID.randomUUID(),
            prompt);
    String output = result == null ? "" : result.getFinalResponse();
    return output == null || output.isBlank() ? "未生成仲裁意见，GlobalReviewer 继续兜底终审。" : output;
  }

  /** 执行全局结果审查 */
  private void reviewResults(TeamRunEntity run, TeamSnapshot team) {
    int remergeAttempts = 0;
    while (true) {
      run.setStatus(TeamRunStatus.GLOBAL_REVIEWING);
      runMapper.upsertById(run);
      recordEvent(
          run.getRunId(),
          null,
          TeamEventType.GLOBAL_REVIEW_STARTED,
          TeamRole.REVIEWER,
          reviewer(team).agentId(),
          "GlobalReviewer 开始终审",
          Map.of(
              "remergeAttempts",
              remergeAttempts,
              "maxMergeAttempts",
              runtimeProperties.getMaxMergeAttempts()));

      ReviewerDecision decision = callReviewerForResults(run, team);
      requireRunWritable(run.getRunId());
      run.setResultReviewJson(json.toJson(decision));
      run.setGlobalReviewJson(json.toJson(decision));
      runMapper.upsertById(run);
      recordEvent(
          run.getRunId(),
          null,
          TeamEventType.GLOBAL_REVIEWED,
          TeamRole.REVIEWER,
          reviewer(team).agentId(),
          decision.reason(),
          decision);

      // 根据审查结果处理
      if (isGlobalSuccess(decision)) {
        completeRunWithFinalOutput(
            run,
            team,
            decision.finalReport().isBlank()
                ? synthesizeFallbackReport(run)
                : decision.finalReport(),
            TeamRole.REVIEWER,
            reviewer(team).agentId(),
            "GlobalReviewer completed final report");
        return;
      }

      if (decision.action() == ReviewerAction.FAILED) {
        failRun(run.getRunId(), "GlobalReviewer 判定失败: " + decision.reason());
        return;
      }

      if (decision.action() == ReviewerAction.REMERGE) {
        if (remergeAttempts >= runtimeProperties.getMaxMergeAttempts()) {
          failRun(
              run.getRunId(),
              "GlobalReviewer requested remerge but merge attempt limit reached: "
                  + decision.reason());
          return;
        }
        remergeAttempts++;
        run.setStatus(TeamRunStatus.MERGING);
        runMapper.upsertById(run);
        TeamMember merger = merger(team);
        String instructions = blankToDefault(decision.revisionInstructions(), decision.reason());

        // 重新聚合
        recordEvent(
            run.getRunId(),
            null,
            TeamEventType.MERGE_STARTED,
            TeamRole.MERGER,
            merger.agentId(),
            "MergeAgent 根据 GlobalReviewer 意见重新聚合",
            Map.of(
                "mergeAttempt", remergeAttempts,
                "mergeInstructions", instructions));
        String mergeOutput = callMerger(run, merger, instructions);
        run.setMergeOutput(mergeOutput);
        runMapper.upsertById(run);
        continue;
      }

      failRun(
          run.getRunId(),
          "GlobalReviewer returned unsupported action "
              + decision.action()
              + ": "
              + decision.reason());
      return;
    }
  }

  /** 调用 Reviewer 审查最终结果 */
  private ReviewerDecision callReviewerForResults(TeamRunEntity run, TeamSnapshot team) {
    TeamMember reviewer = reviewer(team);
    List<TeamStepSnapshot> steps =
        stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
            .map(this::toStepSnapshot)
            .toList();
    return executeReviewerDecision(
        reviewer,
        run,
        TeamPromptFactory.resultReviewer(run, steps, json),
        ReviewerDecisionScope.GLOBAL);
  }

  /** 执行审查决策（重载） */
  ReviewerDecision executeReviewerDecision(
      TeamMember reviewer, TeamRunEntity run, String prompt, boolean requireFinalReport) {
    return executeReviewerDecision(
        reviewer,
        run,
        prompt,
        requireFinalReport ? ReviewerDecisionScope.GLOBAL : ReviewerDecisionScope.PLAN);
  }

  /**
   * 执行审查决策（核心方法）
   *
   * <p>调用 Reviewer Agent 获取结构化决策，并处理格式修复。
   */
  ReviewerDecision executeReviewerDecision(
      TeamMember reviewer, TeamRunEntity run, String prompt, ReviewerDecisionScope scope) {
    // 1. 调用 Reviewer Agent 获取原始响应
    PipelineContext initial =
        executeTeamControlInternal(
            run, reviewerOperation(reviewer), reviewer.agentId(), reviewerSession(run), prompt);
    String raw = initial == null ? "" : initial.getFinalResponse();

    // 2. Reviewer 必须返回结构化 JSON；格式坏了就用同一 Reviewer 做有限次数修复
    for (int attempt = 0; attempt <= runtimeProperties.getMaxReviewerFormatRepairs(); attempt++) {
      try {
        ReviewerDecision decision = json.parseDecision(raw);
        validateReviewerDecision(decision, scope);
        return decision;
      } catch (IllegalArgumentException e) {
        if (attempt >= runtimeProperties.getMaxReviewerFormatRepairs()) {
          throw e;
        }
        // 还有修复机会，让 Reviewer 修复 JSON 格式
        raw = repairReviewerDecisionJson(reviewer, run, prompt, raw, e.getMessage());
      }
    }
    throw new IllegalStateException("Reviewer decision repair exhausted");
  }

  /** 修复审查决策的 JSON 格式 */
  private String repairReviewerDecisionJson(
      TeamMember reviewer,
      TeamRunEntity run,
      String originalPrompt,
      String invalidResponse,
      String parseError) {
    String repairPrompt =
        TeamPromptFactory.reviewerRepair(originalPrompt, invalidResponse, parseError);
    PipelineContext repaired =
        executeTeamControlInternal(
            run,
            OP_REPAIR,
            reviewer.agentId(),
            reviewerSession(run) + "-repair-" + UUID.randomUUID(),
            repairPrompt);
    return repaired == null ? "" : repaired.getFinalResponse();
  }

  /** 获取审查操作标识 */
  private String reviewerOperation(TeamMember reviewer) {
    return reviewer != null && reviewer.role() == TeamRole.SUB_REVIEWER
        ? OP_SUB_REVIEWER
        : OP_REVIEWER;
  }

  /** 执行团队内部调用（启用工具） */
  private PipelineContext executeTeamInternal(
      TeamRunEntity run, String operation, String agentId, String sessionId, String prompt) {
    return executeTeamCall(run, operation, agentId, sessionId, prompt, true);
  }

  /** 执行团队控制调用（禁用工具） */
  private PipelineContext executeTeamControlInternal(
      TeamRunEntity run, String operation, String agentId, String sessionId, String prompt) {
    return executeTeamCall(run, operation, agentId, sessionId, prompt, false);
  }

  /**
   * 执行团队调用（核心方法）
   *
   * <p>处理用量治理、追踪和错误处理。
   */
  private PipelineContext executeTeamCall(
      TeamRunEntity run,
      String operation,
      String agentId,
      String sessionId,
      String prompt,
      boolean toolExposureEnabled) {
    String userId = normalizeUserId(run == null ? null : run.getUserId());
    TeamUsageRecorder recorder = usageRecorder == null ? TeamUsageRecorder.NOOP : usageRecorder;
    PipelineContext result = null;
    TeamUsageReservation reservation = TeamUsageReservation.EMPTY;
    long startedNanos = 0L;
    Span span = EchoMindTrace.startSpan(operation);
    span.setAttribute("echomind.user_id", userId);
    span.setAttribute("echomind.agent_id", nullToBlank(agentId));
    span.setAttribute("echomind.session_id", nullToBlank(sessionId));

    try (Scope ignored = span.makeCurrent()) {
      try {
        recorder.assertAllowed(userId, agentId, sessionId);
        reservation = recorder.reserveUsage(userId, agentId, sessionId, prompt);
        startedNanos = System.nanoTime();
        result =
            executeInternalForUser(
                userId,
                agentId,
                sessionId,
                prompt,
                toolExposureEnabled,
                reservation.pipelineAttributes());
        fillTeamContext(result, userId, agentId, sessionId);
        reservation.attachTo(result);

        if (result != null) {
          span.setAttribute("echomind.model_id", nullToBlank(result.getModelId()));
        }
        boolean failed = result != null && result.hasFailed();
        recorder.record(
            operation,
            userId,
            agentId,
            sessionId,
            result,
            startedNanos,
            failed,
            failed ? result.effectiveFailureReason() : null);
        return result;
      } catch (RuntimeException e) {
        EchoMindTrace.recordException(span, e);
        reservation.attachTo(result);
        recorder.record(
            operation, userId, agentId, sessionId, result, startedNanos, true, e.getMessage());
        if (result == null) {
          recorder.releaseReservations(reservation.allReservationIds());
        }
        throw translateGovernanceRejection(e);
      }
    } finally {
      span.end();
    }
  }

  /** 转换治理拒绝异常 */
  private RuntimeException translateGovernanceRejection(RuntimeException e) {
    if (!(e instanceof ModelInvocationRejectedException rejected)) {
      return e;
    }
    ErrorDetail detail = rejected.errorDetail();
    String code = detail == null ? "" : nullToBlank(detail.code());
    String message =
        detail == null || detail.message() == null || detail.message().isBlank()
            ? e.getMessage()
            : detail.message();
    if ("USER_TOKEN_QUOTA_EXCEEDED".equals(code)) {
      return new TeamUsageQuotaExceededException(message, e);
    }
    if ("PROVIDER_TOKEN_BUDGET_EXCEEDED".equals(code)
        || "TOKEN_RESERVATION_UNAVAILABLE".equals(code)) {
      return new TeamProviderBudgetExceededException(message, e);
    }
    return e;
  }

  /** 填充团队上下文到 PipelineContext */
  private void fillTeamContext(
      PipelineContext ctx, String userId, String agentId, String sessionId) {
    if (ctx == null) {
      return;
    }
    ctx.setUserId(nonBlank(ctx.getUserId(), userId));
    ctx.setAgentId(nonBlank(ctx.getAgentId(), agentId));
    ctx.setSessionId(nonBlank(ctx.getSessionId(), sessionId));
  }

  /** 执行内部调用（带属性） */
  private PipelineContext executeInternalForUser(
      String userId,
      String agentId,
      String sessionId,
      String prompt,
      boolean toolExposureEnabled,
      Map<String, Object> initialAttributes) {
    if (initialAttributes == null || initialAttributes.isEmpty()) {
      return executeInternalForUser(userId, agentId, sessionId, prompt, toolExposureEnabled);
    }
    if ("default".equals(userId)) {
      return toolExposureEnabled
          ? orchestrator.executeInternal(agentId, sessionId, prompt, true, initialAttributes)
          : orchestrator.executeInternal(agentId, sessionId, prompt, false, initialAttributes);
    }
    return orchestrator.executeInternal(
        userId, agentId, sessionId, prompt, toolExposureEnabled, initialAttributes);
  }

  /** 执行内部调用（基础版本） */
  private PipelineContext executeInternalForUser(
      String userId, String agentId, String sessionId, String prompt, boolean toolExposureEnabled) {
    if ("default".equals(userId)) {
      return toolExposureEnabled
          ? orchestrator.executeInternal(agentId, sessionId, prompt)
          : orchestrator.executeInternal(agentId, sessionId, prompt, false);
    }
    return orchestrator.executeInternal(userId, agentId, sessionId, prompt, toolExposureEnabled);
  }

  /** 检查 Run 是否可写（未被删除） */
  private void requireRunWritable(String runId) {
    if (isRunDeleted(runId)) {
      throw new IllegalStateException("Run was deleted: " + runId);
    }
  }

  /** 判断 Run 是否已删除 */
  private boolean isRunDeleted(String runId) {
    return deletedRunIds.contains(runId) || !runMapper.existsById(runId);
  }

  /**
   * 准备步骤重试
   *
   * <p>重试前把上一轮输出、审查原因和修正建议沉淀到 Step，下一轮 Executor prompt 会带上。
   */
  private void prepareStepRetry(
      TeamRunEntity run, TeamStepEntity step, ReviewerDecision decision, TeamMember reviewer) {
    List<String> previous = new ArrayList<>(json.stringList(step.getPreviousOutputsJson()));
    if (step.getRawOutput() != null && !step.getRawOutput().isBlank()) {
      previous.add(step.getRawOutput());
    }
    StepReflection reflection = reflectionForStep(step, decision);
    step.setPreviousOutputsJson(json.toJson(previous));
    step.setRevisionInstructions(
        reflection.revisionInstructions().isBlank()
            ? decision.revisionInstructions()
            : reflection.revisionInstructions());
    step.setLastReviewReason(
        reflection.reviewReason().isBlank() ? decision.reason() : reflection.reviewReason());
    step.setReflectionJson(json.toJson(reflection));
    step.setRetryCount(step.getRetryCount() + 1);
    step.setStatus(TeamStepStatus.RETRYING);
    step.setQualityStatus(TeamStepQualityStatus.RETRY_REQUESTED);
    step.setReviewStatus("RETRY_REQUESTED");
    step.setStartedAt(null);
    step.setCompletedAt(null);
    stepMapper.upsertById(step);

    recordEvent(
        run.getRunId(),
        step.getStepId(),
        TeamEventType.STEP_REFLECTION_RECORDED,
        reviewer.role(),
        reviewer.agentId(),
        "Reviewer 写入 Reflexion 重试上下文",
        reflection);
    recordEvent(
        run.getRunId(),
        step.getStepId(),
        TeamEventType.RETRY_REQUESTED,
        reviewer.role(),
        reviewer.agentId(),
        decision.reason(),
        decision);
  }

  /** 获取步骤的反思信息 */
  private StepReflection reflectionForStep(TeamStepEntity step, ReviewerDecision decision) {
    StepReflection reflection = decision.stepReflections().get(step.getStepId());
    if (reflection == null && step.getClientStepId() != null) {
      reflection = decision.stepReflections().get(step.getClientStepId());
    }
    if (reflection != null) {
      return reflection;
    }
    return new StepReflection(
        step.getAcceptanceCriteria() == null || step.getAcceptanceCriteria().isBlank()
            ? List.of()
            : List.of(step.getAcceptanceCriteria()),
        decision.reason(),
        decision.revisionInstructions(),
        summarize(step.getRawOutput()),
        List.of("不要重复上一轮不满足验收标准的输出", "不要只输出工具调用或搜索关键词"));
  }

  /** 获取依赖步骤的输出 */
  private String dependencyOutputs(TeamStepEntity step) {
    List<String> dependencyIds = json.stringList(step.getDependsOnStepIdsJson());
    if (dependencyIds.isEmpty()) {
      return "";
    }
    List<Map<String, String>> outputs = new ArrayList<>();
    for (TeamStepEntity dependency : stepMapper.selectByRunIdOrderByStepIndexAsc(step.getRunId())) {
      if (dependencyIds.contains(dependency.getStepId())) {
        outputs.add(
            Map.of(
                "stepId", dependency.getStepId(),
                "title", dependency.getTitle(),
                "output", nullToBlank(dependency.getRawOutput())));
      }
    }
    return json.toJson(outputs);
  }

  /** 文本摘要（最多500字符） */
  private String summarize(String value) {
    String text = nullToBlank(value).trim();
    return text.length() <= 500 ? text : text.substring(0, 500) + "...";
  }

  /** 验证审查决策 */
  private void validateReviewerDecision(ReviewerDecision decision, ReviewerDecisionScope scope) {
    ReviewerAction action = decision.action();
    if (action == null) {
      throw new IllegalArgumentException("Reviewer action is required");
    }
    if (!isActionAllowed(action, scope)) {
      throw new IllegalArgumentException(
          "Reviewer action " + action + " is not allowed for " + scope);
    }
    if ((decision.action() == ReviewerAction.REWORK || decision.action() == ReviewerAction.RETRY)
        && scope == ReviewerDecisionScope.STEP
        && decision.revisionInstructions().isBlank()
        && (decision.reason() == null || decision.reason().isBlank())) {
      throw new IllegalStateException(
          "StepReviewer rework requires a reason or revisionInstructions");
    }
    if (decision.action() == ReviewerAction.REMERGE
        && decision.revisionInstructions().isBlank()
        && (decision.reason() == null || decision.reason().isBlank())) {
      throw new IllegalStateException(
          "GlobalReviewer REMERGE requires a reason or revisionInstructions");
    }
    if (decision.action() == ReviewerAction.FAILED
        && (decision.reason() == null || decision.reason().isBlank())) {
      throw new IllegalStateException("Reviewer FAILED requires a reason");
    }
    if (isGlobalSuccess(decision)
        && scope == ReviewerDecisionScope.GLOBAL
        && (decision.finalReport() == null || decision.finalReport().isBlank())) {
      throw new IllegalStateException("GlobalReviewer success requires finalReport");
    }
  }

  /** 判断审查动作是否在指定范围内允许 */
  private boolean isActionAllowed(ReviewerAction action, ReviewerDecisionScope scope) {
    return switch (scope) {
      case PLAN ->
          action == ReviewerAction.CONTINUE
              || action == ReviewerAction.RETRY
              || action == ReviewerAction.FAILED;
      case STEP ->
          action == ReviewerAction.PASS
              || action == ReviewerAction.CONTINUE
              || action == ReviewerAction.REWORK
              || action == ReviewerAction.RETRY
              || action == ReviewerAction.ACCEPT_WITH_RISK
              || action == ReviewerAction.FAILED;
      case GLOBAL ->
          action == ReviewerAction.SUCCESS
              || action == ReviewerAction.CONTINUE
              || action == ReviewerAction.REMERGE
              || action == ReviewerAction.FAILED;
    };
  }

  /** 判断步骤审查是否通过 */
  private boolean isStepPass(ReviewerDecision decision) {
    return decision.action() == ReviewerAction.PASS || decision.action() == ReviewerAction.CONTINUE;
  }

  /** 判断全局审查是否成功 */
  private boolean isGlobalSuccess(ReviewerDecision decision) {
    return decision.action() == ReviewerAction.SUCCESS
        || decision.action() == ReviewerAction.CONTINUE;
  }

  /** 标记步骤失败 */
  @Transactional
  protected void markStepFailed(String runId, String stepId, String message) {
    if (isRunDeleted(runId)) {
      return;
    }
    TeamStepEntity step = stepMapper.selectOptionalById(stepId).orElse(null);
    if (step != null) {
      if (isStepTerminal(step.getStatus())) {
        log.info("Skip failing step {} for run {} because it is already terminal", stepId, runId);
        return;
      }
      step.setStatus(TeamStepStatus.FAILED);
      step.setRawOutput("[Error] " + nullToBlank(message));
      step.setCompletedAt(Instant.now());
      stepMapper.upsertById(step);
    }
    recordEvent(
        runId,
        stepId,
        TeamEventType.STEP_FAILED,
        TeamRole.EXECUTOR,
        null,
        "Step failed: " + nullToBlank(message),
        null);
  }

  /** 标记步骤超时 */
  @Transactional
  protected void markStepTimedOut(String runId, String stepId) {
    if (isRunDeleted(runId)) {
      return;
    }
    String message =
        "Step execution timed out after " + runtimeProperties.getStepTimeoutSeconds() + " seconds";
    TeamStepEntity step = stepMapper.selectOptionalById(stepId).orElse(null);
    if (step != null && !isStepTerminal(step.getStatus())) {
      step.setStatus(TeamStepStatus.FAILED);
      step.setRawOutput("[Error] " + nullToBlank(message));
      step.setCompletedAt(Instant.now());
      stepMapper.upsertById(step);
    }
    recordEvent(
        runId,
        stepId,
        TeamEventType.STEP_FAILED,
        TeamRole.EXECUTOR,
        null,
        "Step timed out: " + nullToBlank(message),
        null);
  }

  @Transactional
  protected void failRun(String runId, String message) {
    TeamRunEntity run = runMapper.selectOptionalById(runId).orElse(null);
    if (run != null && !isTerminal(run.getStatus())) {
      run.setStatus(TeamRunStatus.FAILED);
      run.setFinalOutput("[Error] " + nullToBlank(message));
      runMapper.upsertById(run);
    }
    recordEvent(
        runId,
        null,
        TeamEventType.RUN_FAILED,
        null,
        null,
        "Run failed: " + nullToBlank(message),
        null);
  }

  private void replaceSteps(TeamRunEntity run, List<PlannedStep> planned) {
    replaceSteps(run, planned, false);
  }

  private void replaceSteps(
      TeamRunEntity run, List<PlannedStep> planned, boolean preserveExisting) {
    int baseIndex = 0;
    if (preserveExisting) {
      for (TeamStepEntity existing : stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId())) {
        if (existing.getStatus() != TeamStepStatus.SUPERSEDED) {
          existing.setStatus(TeamStepStatus.SUPERSEDED);
          stepMapper.upsertById(existing);
        }
        baseIndex = Math.max(baseIndex, existing.getStepIndex());
      }
    } else {
      stepMapper.deleteByRunId(run.getRunId());
    }

    List<PlannedStep> executable =
        planned.stream().filter(item -> !isFinalIntegrationStep(item)).toList();
    validatePlannedDag(executable);

    boolean simpleDraft = run.getTaskLevel() == TeamTaskLevel.SIMPLE && executable.size() == 1;
    Map<String, String> clientToStepId = new LinkedHashMap<>();
    for (int index = 1; index <= executable.size(); index++) {
      PlannedStep item = executable.get(index - 1);
      String clientStepId = simpleDraft ? "simple-draft" : clientStepId(item, index);
      clientToStepId.put(
          clientStepId, "step-" + index + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    int index = 1;
    for (PlannedStep item : executable) {
      String clientStepId = simpleDraft ? "simple-draft" : clientStepId(item, index);
      List<String> dependencyStepIds =
          item.dependsOn().stream()
              .map(clientToStepId::get)
              .filter(id -> id != null && !id.isBlank())
              .toList();

      TeamStepEntity step = new TeamStepEntity();
      step.setStepId(clientToStepId.get(clientStepId));
      step.setRunId(run.getRunId());
      step.setStepIndex(baseIndex + index);
      step.setClientStepId(clientStepId);
      step.setTitle(item.title());
      step.setDescription(item.description());
      step.setRequiredCapabilitiesJson(json.toJson(item.requiredCapabilities()));
      step.setDependsOnStepIdsJson(json.toJson(dependencyStepIds));
      TeamRiskDecision riskDecision = riskPolicy.decide(item);
      step.setRiskLevel(riskDecision.level());
      step.setRiskReason(riskDecision.reason());
      step.setAcceptanceCriteria(item.acceptanceCriteria());
      step.setStatus(TeamStepStatus.PENDING);
      step.setQualityStatus(TeamStepQualityStatus.PENDING);
      step.setPlanIteration(
          run.getFullReplanCount() + run.getPartialReplanCount() + run.getResultReplanCount());
      stepMapper.upsertById(step);

      recordEvent(
          run.getRunId(),
          step.getStepId(),
          TeamEventType.RISK_DECIDED,
          null,
          null,
          "RiskPolicy 判定为"
              + (riskDecision.level() == TeamRiskLevel.HIGH ? "高风险" : "低风险")
              + "："
              + riskDecision.reason(),
          riskDecision);
      index++;
    }
  }

  private String clientStepId(PlannedStep item, int index) {
    return item.clientStepId() == null || item.clientStepId().isBlank()
        ? "step-" + index
        : item.clientStepId().trim();
  }

  private void validatePlannedDag(List<PlannedStep> planned) {
    if (planned == null || planned.isEmpty()) {
      throw new IllegalStateException("Planner did not produce executable steps");
    }
    Set<String> ids = new HashSet<>();
    for (int i = 0; i < planned.size(); i++) {
      PlannedStep step = planned.get(i);
      String clientStepId = clientStepId(step, i + 1);
      if (!ids.add(clientStepId)) {
        throw new IllegalStateException("Planner produced duplicate clientStepId: " + clientStepId);
      }
    }
    for (int i = 0; i < planned.size(); i++) {
      PlannedStep step = planned.get(i);
      String clientStepId = clientStepId(step, i + 1);
      for (String dependency : step.dependsOn()) {
        if (!ids.contains(dependency)) {
          throw new IllegalStateException("Planner produced unknown dependency: " + dependency);
        }
        if (clientStepId.equals(dependency)) {
          throw new IllegalStateException("Planner produced self dependency: " + clientStepId);
        }
      }
    }
    if (hasCycle(planned)) {
      throw new IllegalStateException("Planner produced dependency cycle");
    }
  }

  private boolean hasCycle(List<PlannedStep> planned) {
    Map<String, List<String>> graph = new LinkedHashMap<>();
    for (int i = 0; i < planned.size(); i++) {
      PlannedStep step = planned.get(i);
      graph.put(clientStepId(step, i + 1), step.dependsOn());
    }
    Set<String> visiting = new HashSet<>();
    Set<String> visited = new HashSet<>();
    for (String id : graph.keySet()) {
      if (hasCycle(id, graph, visiting, visited)) {
        return true;
      }
    }
    return false;
  }

  private boolean hasCycle(
      String id, Map<String, List<String>> graph, Set<String> visiting, Set<String> visited) {
    if (visited.contains(id)) {
      return false;
    }
    if (!visiting.add(id)) {
      return true;
    }
    for (String dependency : graph.getOrDefault(id, List.of())) {
      if (hasCycle(dependency, graph, visiting, visited)) {
        return true;
      }
    }
    visiting.remove(id);
    visited.add(id);
    return false;
  }

  private boolean isFinalIntegrationStep(PlannedStep item) {
    if (item == null) {
      return false;
    }
    String text =
        (nullToBlank(item.title())
                + " "
                + nullToBlank(item.description())
                + " "
                + nullToBlank(item.acceptanceCriteria()))
            .toLowerCase();
    return containsAny(
        text,
        "最终报告",
        "最终方案",
        "可执行方案",
        "整合输出",
        "综合方案",
        "汇总输出",
        "汇总成",
        "整合成",
        "撰写完整",
        "final report",
        "final deliverable",
        "integration",
        "integrated report");
  }

  private boolean containsAny(String text, String... values) {
    if (text == null || text.isBlank()) {
      return false;
    }
    for (String value : values) {
      if (text.contains(value)) {
        return true;
      }
    }
    return false;
  }

  @Transactional
  protected void recordEvent(
      String runId,
      String stepId,
      TeamEventType type,
      TeamRole actorRole,
      String actorAgentId,
      String message,
      Object payload) {
    if (isRunDeleted(runId)) {
      log.debug("Skip team event for deleted run: {}", runId);
      return;
    }
    TeamEventEntity event = new TeamEventEntity();
    event.setRunId(runId);
    event.setStepId(stepId);
    event.setType(type);
    event.setActorRole(actorRole);
    event.setActorAgentId(actorAgentId);
    event.setMessage(message);
    event.setPayloadJson(payload == null ? null : json.toJson(payload));
    eventMapper.upsertById(event);
  }

  private List<TeamMemberSpec> normalizeSpecs(List<TeamMemberSpec> specs) {
    if (specs == null) {
      return List.of();
    }
    int fallbackOrder = 10;
    List<TeamMemberSpec> normalized = new ArrayList<>();
    for (TeamMemberSpec spec : specs) {
      if (spec == null) {
        continue;
      }
      normalized.add(
          new TeamMemberSpec(
              spec.agentId(),
              spec.role(),
              spec.capabilityTags() == null ? List.of() : spec.capabilityTags(),
              spec.sortOrder() == 0 ? fallbackOrder : spec.sortOrder()));
      fallbackOrder += 10;
    }
    return normalized;
  }

  private void validateMembers(List<TeamMemberSpec> specs) {
    long planners = specs.stream().filter(spec -> spec.role() == TeamRole.PLANNER).count();
    long executors = specs.stream().filter(spec -> spec.role() == TeamRole.EXECUTOR).count();
    long reviewers = specs.stream().filter(spec -> spec.role() == TeamRole.REVIEWER).count();
    if (planners != 1 || executors < 1 || reviewers != 1) {
      throw new IllegalArgumentException(
          "Team requires exactly one Planner, at least one Executor, and exactly one Reviewer");
    }
    for (TeamMemberSpec spec : specs) {
      if (spec.agentId() == null || spec.agentId().isBlank()) {
        throw new IllegalArgumentException("member agentId is required");
      }
      if (agentFactory.get(spec.agentId()) == null) {
        throw new IllegalArgumentException("Agent not found: " + spec.agentId());
      }
    }
  }

  private TeamRunEntity loadRun(String teamId, String userId, String runId) {
    TeamRunEntity run =
        runMapper
            .selectOptionalById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    if (!run.getTeamId().equals(teamId)) {
      throw new IllegalArgumentException("Run does not belong to team: " + runId);
    }
    if (!normalizeUserId(run.getUserId()).equals(normalizeUserId(userId))) {
      throw new IllegalArgumentException("Run does not belong to current user: " + runId);
    }
    loadTeamForOwner(teamId, userId);
    return run;
  }

  private TeamEntity loadTeamForOwner(String teamId, String userId) {
    return teamMapper
        .selectOptionalByTeamIdAndOwnerUserId(teamId, normalizeUserId(userId))
        .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
  }

  private TeamSnapshot toTeamSnapshot(TeamEntity team) {
    List<TeamMember> members =
        memberMapper.selectByTeamIdOrderBySortOrderAscIdAsc(team.getTeamId()).stream()
            .map(this::toMember)
            .toList();
    return new TeamSnapshot(
        team.getTeamId(),
        team.getOwnerUserId(),
        team.getName(),
        members,
        team.getCreatedAt(),
        team.getUpdatedAt());
  }

  private TeamMember toMember(TeamMemberEntity entity) {
    Agent agent = agentFactory.get(entity.getAgentId());
    return new TeamMember(
        entity.getAgentId(),
        agent == null ? entity.getAgentId() : agent.getName(),
        entity.getRole(),
        json.stringList(entity.getCapabilityTagsJson()),
        entity.getSortOrder(),
        agent);
  }

  private TeamRunSnapshot toRunSnapshot(
      TeamRunEntity run, boolean includeSteps, boolean includeEvents) {
    List<TeamStepSnapshot> steps =
        includeSteps
            ? stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
                .map(this::toStepSnapshot)
                .toList()
            : List.of();
    List<TeamEventSnapshot> events =
        includeEvents
            ? eventMapper.selectByRunIdOrderByCreatedAtAscIdAsc(run.getRunId()).stream()
                .map(this::toEventSnapshot)
                .toList()
            : List.of();
    return new TeamRunSnapshot(
        run.getRunId(),
        run.getTeamId(),
        run.getUserId(),
        run.getTask(),
        run.getStatus(),
        run.getTaskLevel() == null ? TeamTaskLevel.COMPLEX.name() : run.getTaskLevel().name(),
        reviewOptions(run),
        run.getPlanReviewJson(),
        run.getResultReviewJson(),
        run.getMergeOutput(),
        run.getGlobalReviewJson(),
        run.getConflictReportJson(),
        run.getArbitrationJson(),
        run.getFinalOutput(),
        includeSteps ? dynamicMermaid(run, steps, events) : run.getMermaidDiagram(),
        run.getPlanRetryCount(),
        run.getResultReplanCount(),
        run.getPartialReplanCount(),
        run.getFullReplanCount(),
        run.getArbitrationCount(),
        steps,
        events,
        run.getCreatedAt(),
        run.getUpdatedAt());
  }

  private TeamStepSnapshot toStepSnapshot(TeamStepEntity step) {
    return new TeamStepSnapshot(
        step.getStepId(),
        step.getStepIndex(),
        step.getClientStepId(),
        step.getTitle(),
        step.getDescription(),
        json.stringList(step.getRequiredCapabilitiesJson()),
        json.stringList(step.getDependsOnStepIdsJson()),
        step.getRiskLevel(),
        step.getRiskReason(),
        step.getAcceptanceCriteria(),
        step.getAssignedAgentId(),
        step.getStatus(),
        step.getRawOutput(),
        step.getRevisionInstructions(),
        step.getReviewStatus(),
        step.getQualityStatus(),
        step.getSubReviewJson(),
        step.getLastReviewReason(),
        step.getReflectionJson(),
        step.getPlanIteration(),
        step.getRetryCount(),
        step.getStartedAt(),
        step.getCompletedAt());
  }

  private String dynamicMermaid(
      TeamRunEntity run, List<TeamStepSnapshot> steps, List<TeamEventSnapshot> events) {
    if (steps == null || steps.isEmpty()) {
      return run.getMermaidDiagram();
    }
    TeamEntity team = loadTeamForOwner(run.getTeamId(), run.getUserId());
    return MermaidGenerator.generateFromSnapshots(
        team.getName(),
        run.getStatus(),
        run.getTaskLevel() == null ? TeamTaskLevel.COMPLEX.name() : run.getTaskLevel().name(),
        steps,
        events == null ? List.of() : events);
  }

  private TeamEventSnapshot toEventSnapshot(TeamEventEntity event) {
    return new TeamEventSnapshot(
        event.getId(),
        event.getRunId(),
        event.getStepId(),
        event.getType(),
        event.getActorRole(),
        event.getActorAgentId(),
        event.getMessage(),
        event.getPayloadJson(),
        event.getCreatedAt());
  }

  private TeamMember planner(TeamSnapshot team) {
    return member(team, TeamRole.PLANNER);
  }

  private TeamMember reviewer(TeamSnapshot team) {
    return member(team, TeamRole.REVIEWER);
  }

  private TeamMember subReviewer(TeamSnapshot team) {
    return optionalMember(team, TeamRole.SUB_REVIEWER);
  }

  private TeamMember merger(TeamSnapshot team) {
    return optionalMember(team, TeamRole.MERGER);
  }

  private TeamMember member(TeamSnapshot team, TeamRole role) {
    return team.members().stream()
        .filter(item -> item.role() == role)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Missing team role: " + role));
  }

  private TeamMember executorById(TeamSnapshot team, String agentId) {
    return team.members().stream()
        .filter(item -> item.role() == TeamRole.EXECUTOR)
        .filter(item -> item.agentId().equals(agentId))
        .findFirst()
        .orElse(null);
  }

  private TeamMember executorBySelection(TeamSnapshot team, TeamAgentSelection selection) {
    if (selection == null) {
      return null;
    }
    String memberKey = selection.memberKey();
    if (memberKey != null && !memberKey.isBlank()) {
      TeamMember byKey =
          team.members().stream()
              .filter(item -> item.role() == TeamRole.EXECUTOR)
              .filter(item -> (item.agentId() + "#" + item.sortOrder()).equalsIgnoreCase(memberKey))
              .findFirst()
              .orElse(null);
      if (byKey != null) {
        return byKey;
      }
    }
    return executorById(team, selection.agentId());
  }

  private TeamMember optionalMember(TeamSnapshot team, TeamRole role) {
    return team.members().stream()
        .filter(item -> item.role() == role)
        .findFirst()
        .orElseGet(() -> reviewer(team));
  }

  private TeamReviewOptions reviewOptions(TeamRunEntity run) {
    if (run == null) {
      return TeamReviewOptions.QUALITY_FIRST;
    }
    return new TeamReviewOptions(
        run.isPlanReviewEnabled(),
        run.isSubReviewEnabled(),
        run.isGlobalReviewEnabled(),
        run.isSimpleFastPathEnabled());
  }

  private ReviewerDecision skippedReviewDecision(String reason, String finalReport) {
    return ReviewerDecision.continueWith(reason, finalReport);
  }

  private void completeRunWithFinalOutput(
      TeamRunEntity run,
      TeamSnapshot team,
      String finalOutput,
      TeamRole actorRole,
      String actorAgentId,
      String eventMessage) {
    run.setFinalOutput(
        finalOutput == null || finalOutput.isBlank() ? synthesizeFallbackReport(run) : finalOutput);
    run.setStatus(TeamRunStatus.COMPLETED);
    run.setMermaidDiagram(
        MermaidGenerator.generateFromSnapshots(
            team.name(),
            TeamRunStatus.COMPLETED,
            run.getTaskLevel() == null ? TeamTaskLevel.COMPLEX.name() : run.getTaskLevel().name(),
            toRunSnapshot(run, true, true).steps(),
            toRunSnapshot(run, true, true).events()));
    runMapper.upsertById(run);
    recordEvent(
        run.getRunId(),
        null,
        TeamEventType.RUN_COMPLETED,
        actorRole,
        actorAgentId,
        eventMessage,
        null);
  }

  private String synthesizeFallbackReport(TeamRunEntity run) {
    StringBuilder output = new StringBuilder();
    output.append("任务完成。\n\n");
    for (TeamStepEntity step : stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId())) {
      output
          .append("## ")
          .append(step.getTitle())
          .append("\n")
          .append(nullToBlank(step.getRawOutput()))
          .append("\n\n");
    }
    return output.toString().trim();
  }

  private boolean isTerminal(TeamRunStatus status) {
    return status == TeamRunStatus.COMPLETED || status == TeamRunStatus.FAILED;
  }

  private void scheduleRun(String runId) {
    if (teamStepCommandProducer == null) {
      failRun(runId, "TeamStepCommandProducer not available");
      return;
    }
    if (org.springframework.transaction.support.TransactionSynchronizationManager
            .isSynchronizationActive()
        && org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive()) {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .registerSynchronization(
              new org.springframework.transaction.support.TransactionSynchronization() {
                @Override
                public void afterCommit() {
                  publishRunStarted(runId);
                }
              });
      return;
    }
    publishRunStarted(runId);
  }

  private void publishRunStarted(String runId) {
    try {
      RunStarted event =
          new RunStarted(UUID.randomUUID().toString(), runId, null, Instant.now(), 0, null);
      teamStepCommandProducer.publishRunEvent(runId, event);
      log.info("Published RunStarted for run {}", runId);
    } catch (Exception e) {
      log.error("Failed to publish RunStarted for run {}", runId, e);
      failRun(runId, e.getMessage());
    }
  }

  private String plannerSession(TeamRunEntity run) {
    return "team-run-" + run.getRunId() + "-planner";
  }

  private String reviewerSession(TeamRunEntity run) {
    return "team-run-" + run.getRunId() + "-reviewer";
  }

  private String stepSession(TeamRunEntity run, TeamStepEntity step) {
    return "team-run-" + run.getRunId() + "-step-" + step.getStepId();
  }

  private String nullToBlank(String value) {
    return value == null ? "" : value;
  }

  private String blankToDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value.trim();
  }

  private String nonBlank(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private String normalizeUserId(String userId) {
    return userId == null || userId.isBlank() ? "default" : userId;
  }

  @Transactional
  public void planAndReviewForCoordinator(String runId) {
    TeamRunEntity run =
        runMapper
            .selectOptionalById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    if (isTerminal(run.getStatus())) {
      log.info("Run {} already terminal, skip planAndReviewForCoordinator", runId);
      return;
    }
    if (run.getStatus() == TeamRunStatus.PENDING && !runMapper.tryAcquireExecuteLock(runId)) {
      log.info("Run {} execute lock was not acquired, skip", runId);
      return;
    }
    run =
        runMapper
            .selectOptionalById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    TeamSnapshot team = getTeam(run.getTeamId());
    List<TeamStepEntity> steps = stepMapper.selectByRunIdOrderByStepIndexAsc(runId);
    if (steps.isEmpty()) {
      planAndReview(run, team);
      steps = stepMapper.selectByRunIdOrderByStepIndexAsc(runId);
    }
    run =
        runMapper
            .selectOptionalById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
    if (isTerminal(run.getStatus())) {
      log.info("Run {} became terminal during planning, skip DAG initialization", runId);
      return;
    }
    markInitialStepStates(runId, steps);
    markRunExecutingFromCoordinator(run);
    steps = stepMapper.selectByRunIdOrderByStepIndexAsc(runId);
    if (dagStoreInstance != null && !steps.isEmpty()) {
      dagStoreInstance.initializeDag(runId, steps, runtimeProperties.getMaxConcurrentSteps());
      dagStoreInstance.setDagStatus(runId, "EXECUTING");
    }
  }

  private void markRunExecutingFromCoordinator(TeamRunEntity run) {
    if (run.getStatus() == TeamRunStatus.EXECUTING || isTerminal(run.getStatus())) {
      return;
    }
    run.setStatus(TeamRunStatus.EXECUTING);
    runMapper.upsertById(run);
    recordEvent(
        run.getRunId(),
        null,
        TeamEventType.TEAM_CONTROL_STARTED,
        null,
        null,
        "DAG execution started",
        Map.of("coordinator", true));
  }

  private void markInitialStepStates(String runId, List<TeamStepEntity> steps) {
    if (steps == null || steps.isEmpty()) {
      return;
    }
    for (TeamStepEntity step : steps) {
      if (step.getStatus() != TeamStepStatus.PENDING) {
        continue;
      }
      List<String> dependencies = json.stringList(step.getDependsOnStepIdsJson());
      if (dependencies.isEmpty()) {
        step.setStatus(TeamStepStatus.READY);
        stepMapper.upsertById(step);
        recordEvent(
            runId,
            step.getStepId(),
            TeamEventType.STEP_READY,
            null,
            null,
            "Root step ready for execution",
            null);
      } else {
        step.setStatus(TeamStepStatus.BLOCKED);
        stepMapper.upsertById(step);
        recordEvent(
            runId,
            step.getStepId(),
            TeamEventType.STEP_BLOCKED,
            null,
            null,
            "Step waiting for dependencies",
            Map.of("dependsOnStepIds", dependencies));
      }
    }
  }

  private void repairStepStatesFromMysql(String runId, List<TeamStepEntity> steps) {
    if (steps == null || steps.isEmpty()) {
      return;
    }
    Set<String> completedStepIds =
        steps.stream()
            .filter(step -> step.getStatus() == TeamStepStatus.COMPLETED)
            .map(TeamStepEntity::getStepId)
            .collect(java.util.stream.Collectors.toSet());
    for (TeamStepEntity step : steps) {
      if (isStepTerminal(step.getStatus())) {
        continue;
      }
      List<String> dependencies = json.stringList(step.getDependsOnStepIdsJson());
      boolean ready = completedStepIds.containsAll(dependencies);
      TeamStepStatus repairedStatus = ready ? TeamStepStatus.READY : TeamStepStatus.BLOCKED;
      if (step.getStatus() == repairedStatus) {
        continue;
      }
      step.setStatus(repairedStatus);
      step.setAssignedAgentId(null);
      step.setStartedAt(null);
      step.setCompletedAt(null);
      stepMapper.upsertById(step);
      recordEvent(
          runId,
          step.getStepId(),
          ready ? TeamEventType.STEP_READY : TeamEventType.STEP_BLOCKED,
          null,
          null,
          ready ? "Step repaired as ready for execution" : "Step repaired as waiting for dependencies",
          Map.of("repair", true, "dependsOnStepIds", dependencies));
    }
  }

  @Transactional
  public void markStepsReadyFromCoordinator(String runId, List<String> stepIds) {
    if (stepIds == null || stepIds.isEmpty() || isRunDeleted(runId)) {
      return;
    }
    for (TeamStepEntity step : stepMapper.selectByRunIdOrderByStepIndexAsc(runId)) {
      if (stepIds.contains(step.getStepId()) && step.getStatus() == TeamStepStatus.BLOCKED) {
        step.setStatus(TeamStepStatus.READY);
        stepMapper.upsertById(step);
      }
    }
  }

  @Transactional
  public void markStepRetryingFromCoordinator(
      String runId, String stepId, int retryCount, String message) {
    if (isRunDeleted(runId)) {
      return;
    }
    TeamStepEntity step = stepMapper.selectOptionalById(stepId).orElse(null);
    if (step == null || isStepTerminal(step.getStatus())) {
      return;
    }
    step.setStatus(TeamStepStatus.RETRYING);
    step.setRetryCount(Math.max(step.getRetryCount(), retryCount));
    step.setLastReviewReason(message);
    step.setCompletedAt(null);
    stepMapper.upsertById(step);
    recordEvent(
        runId,
        stepId,
        TeamEventType.RETRY_REQUESTED,
        TeamRole.EXECUTOR,
        null,
        "Step scheduled for retry: " + nullToBlank(message),
        Map.of("retryCount", step.getRetryCount()));
  }

  @Transactional
  public void onDagCompleteInCoordinator(String runId) {
    TeamRunEntity run = runMapper.selectOptionalById(runId).orElse(null);
    if (run == null || isTerminal(run.getStatus())) {
      return;
    }
    List<TeamStepEntity> active = activeSteps(runId);
    if (active.stream().anyMatch(step -> step.getStatus() != TeamStepStatus.COMPLETED)) {
      log.warn("Run {} DAG reported complete but MySQL still has unfinished steps", runId);
      return;
    }
    mergeAndReviewResults(run, getTeam(run.getTeamId()));
    if (dagStoreInstance != null) {
      dagStoreInstance.destroyDag(runId);
    }
  }

  @Transactional
  public void failRunFromCoordinator(String runId, String message) {
    failRun(runId, message);
    if (dagStoreInstance != null) {
      dagStoreInstance.destroyDag(runId);
    }
  }

  @Transactional
  public void markStepFailedFromCompensator(String runId, String stepId, String message) {
    markStepFailed(runId, stepId, message);
  }

  public void dispatchReadyStepsFromBlackboard(String runId) {
    if (dagStoreInstance == null || teamStepCommandProducer == null) {
      return;
    }
    String stopping = dagStoreInstance.getControlFlag(runId, "stopping");
    if (stopping != null) {
      log.info("Run {} is stopping ({}), skip repair dispatch", runId, stopping);
      return;
    }
    while (dagStoreInstance.hasPendingReady(runId)) {
      String claimed = dagStoreInstance.tryClaimSlot(runId);
      if (claimed == null) {
        return;
      }
      log.info("Repair dispatch step {} for run {}", claimed, runId);
      teamStepCommandProducer.publishExecuteStep(runId, claimed);
    }
  }

  private void scheduleReadyStepDispatch(String runId) {
    if (org.springframework.transaction.support.TransactionSynchronizationManager
            .isSynchronizationActive()
        && org.springframework.transaction.support.TransactionSynchronizationManager
            .isActualTransactionActive()) {
      org.springframework.transaction.support.TransactionSynchronizationManager
          .registerSynchronization(
              new org.springframework.transaction.support.TransactionSynchronization() {
                @Override
                public void afterCommit() {
                  dispatchReadyStepsFromBlackboard(runId);
                }
              });
      return;
    }
    dispatchReadyStepsFromBlackboard(runId);
  }

  @Transactional
  public void executeStepPublic(String runId, String stepId) {
    executeStep(runId, stepId);
  }

  @Transactional(readOnly = true)
  public StepExecutionOutcome stepExecutionOutcome(String stepId) {
    TeamStepEntity step = stepMapper.selectOptionalById(stepId).orElse(null);
    if (step == null) {
      return StepExecutionOutcome.missing(stepId);
    }
    TeamRunEntity run = runMapper.selectOptionalById(step.getRunId()).orElse(null);
    return StepExecutionOutcome.from(step, run == null ? null : run.getStatus());
  }

  public int getMaxStepRetries() {
    return runtimeProperties.getMaxStepRetries();
  }

  private TeamRedisDagStore dagStoreInstance;

  public void setDagStore(TeamRedisDagStore dagStore) {
    this.dagStoreInstance = dagStore;
  }
}
