package com.echomind.agent.team.runtime;

import com.echomind.agent.Agent;
import com.echomind.agent.AgentFactory;
import com.echomind.agent.orchestration.AgentOrchestrator;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.team.model.PlannedStep;
import com.echomind.agent.team.model.ReviewerDecision;
import com.echomind.agent.team.model.StepReflection;
import com.echomind.agent.team.model.TeamMember;
import com.echomind.agent.team.state.ReviewerAction;
import com.echomind.agent.team.state.TeamClarificationStage;
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
import com.echomind.agent.team.store.TeamMemberEntity;
import com.echomind.agent.team.store.TeamMemberMapper;
import com.echomind.agent.team.store.TeamMapper;
import com.echomind.agent.team.store.TeamRunEntity;
import com.echomind.agent.team.store.TeamRunMapper;
import com.echomind.agent.team.store.TeamStepEntity;
import com.echomind.agent.team.store.TeamStepMapper;
import com.echomind.agent.team.visualization.MermaidGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Agent Team 的 MySQL 黑板和异步状态机服务。
 *
 * <p>这里保留 Team Run 的事务性编排：创建 Run、推进 DAG、写 Step/Event、生成快照。
 * Prompt 文本放在 {@link TeamPromptFactory}，Executor 选择放在 {@link TeamAgentSelector}，
 * 本类尽量只处理状态流转和持久化。</p>
 */
@Slf4j
@Service
public class TeamBlackboardService {

    // 数据库操作
    private final TeamMapper teamMapper;
    // 团队成员操作
    private final TeamMemberMapper memberMapper;
    // 团队运行操作
    private final TeamRunMapper runMapper;
    // 团队步骤操作
    private final TeamStepMapper stepMapper;
    // 团队时间操作
    private final TeamEventMapper eventMapper;
    // agent创建工厂
    private final AgentFactory agentFactory;
    // agent 编排器
    private final AgentOrchestrator orchestrator;
    // 任务执行器
    private final TaskExecutor taskExecutor;
    // 运行时属性
    private final TeamRuntimeProperties runtimeProperties;

    // JSON 支持类
    private final TeamJsonSupport json = new TeamJsonSupport();
    // agent选择器(根据tool来进行挑选)
    private final TeamAgentSelector agentSelector = new TeamAgentSelector();
    // 风险策略
    private final TeamRiskPolicy riskPolicy = new TeamRiskPolicy();

    // 删除 Team/Run 后，后台异步任务可能仍在收尾；用它阻止继续写事件。
    private final Set<String> deletedRunIds = ConcurrentHashMap.newKeySet();
    private final Set<String> stoppingRunIds = ConcurrentHashMap.newKeySet();
    private final Map<String, String> pendingClarifications = new ConcurrentHashMap<>();

    public TeamBlackboardService(TeamMapper teamMapper,
                                 TeamMemberMapper memberMapper,
                                 TeamRunMapper runMapper,
                                 TeamStepMapper stepMapper,
                                 TeamEventMapper eventMapper,
                                 AgentFactory agentFactory,
                                 AgentOrchestrator orchestrator,
                                 @Qualifier("teamTaskExecutor") TaskExecutor taskExecutor) {
        this(teamMapper, memberMapper, runMapper, stepMapper, eventMapper,
            agentFactory, orchestrator, taskExecutor, new TeamRuntimeProperties());
    }

    @Autowired
    public TeamBlackboardService(TeamMapper teamMapper,
                                 TeamMemberMapper memberMapper,
                                 TeamRunMapper runMapper,
                                 TeamStepMapper stepMapper,
                                 TeamEventMapper eventMapper,
                                 AgentFactory agentFactory,
                                 AgentOrchestrator orchestrator,
                                 @Qualifier("teamTaskExecutor") TaskExecutor taskExecutor,
                                 TeamRuntimeProperties runtimeProperties) {
        this.teamMapper = teamMapper;
        this.memberMapper = memberMapper;
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
        this.eventMapper = eventMapper;
        this.agentFactory = agentFactory;
        this.orchestrator = orchestrator;
        this.taskExecutor = taskExecutor;
        this.runtimeProperties = runtimeProperties == null ? new TeamRuntimeProperties() : runtimeProperties;
    }

    @Transactional(readOnly = true)
    public List<TeamSnapshot> listTeams() {
        return listTeams("default");
    }

    @Transactional(readOnly = true)
    public List<TeamSnapshot> listTeams(String userId) {
        return teamMapper.selectByOwnerUserIdOrderByCreatedAtAsc(userId).stream()
            .map(this::toTeamSnapshot)
            .toList();
    }

    @Transactional(readOnly = true)
    // 根据团队id获取团队快照
    public TeamSnapshot getTeam(String teamId) {
        return teamMapper.selectOptionalById(teamId)
            .map(this::toTeamSnapshot)
            .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
    }

    @Transactional
    public TeamSnapshot createTeam(String requestedName, List<TeamMemberSpec> specs) {
        return createTeam("default", requestedName, specs);
    }

    @Transactional
    public TeamSnapshot createTeam(String userId, String requestedName, List<TeamMemberSpec> specs) {
        List<TeamMemberSpec> normalized = normalizeSpecs(specs);
        validateMembers(normalized);

        TeamEntity team = new TeamEntity();
        team.setTeamId(UUID.randomUUID().toString());
        team.setOwnerUserId(userId);
        team.setName(requestedName == null || requestedName.isBlank()
            ? "Team-" + UUID.randomUUID().toString().substring(0, 8)
            : requestedName.trim());
        teamMapper.upsertById(team);

        // 创建团队成员实体
        for (TeamMemberSpec spec : normalized) {
            TeamMemberEntity member = new TeamMemberEntity();
            member.setTeamId(team.getTeamId());
            member.setAgentId(spec.agentId());
            member.setRole(spec.role());
            member.setCapabilityTagsJson(json.toJson(spec.capabilityTags() == null ? List.of() : spec.capabilityTags()));
            member.setSortOrder(spec.sortOrder());
            memberMapper.upsertById(member);
        }

        return getTeam(team.getTeamId());
    }

    @Transactional
    public void deleteTeam(String teamId) {
        deleteTeam(teamId, "default");
    }

    @Transactional
    public void deleteTeam(String teamId, String userId) {
        if (teamId == null || teamId.isBlank()) {
            throw new IllegalArgumentException("teamId is required");
        }
        TeamEntity team = teamMapper.selectOptionalById(teamId)
            .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
        if (!team.getOwnerUserId().equals(userId)) {
            throw new IllegalArgumentException("Not authorized to delete this team");
        }
        List<String> runIds = runMapper.selectByTeamIdOrderByCreatedAtDesc(team.getTeamId()).stream()
            .map(TeamRunEntity::getRunId)
            .toList();
        deletedRunIds.addAll(runIds);
        for (String runId : runIds) {
            pendingClarifications.remove(runId);
        }
        if (!runIds.isEmpty()) {
            eventMapper.deleteByRunIdIn(runIds);
            stepMapper.deleteByRunIdIn(runIds);
        }
        runMapper.deleteByTeamId(team.getTeamId());
        memberMapper.deleteByTeamId(team.getTeamId());
        teamMapper.deleteEntity(team);
    }

    String buildPlannerPrompt(TeamRunEntity run, TeamSnapshot team, List<PlannedStep> previousPlan,
                              String plannerRevisionInstructions) {
        return buildPlannerPrompt(run, team, previousPlan, plannerRevisionInstructions, List.of());
    }

    String buildPlannerPrompt(TeamRunEntity run, TeamSnapshot team, List<PlannedStep> previousPlan,
                              String plannerRevisionInstructions,
                              List<TeamStepSnapshot> previousExecutionResults) {
        return TeamPromptFactory.planner(run, team, previousPlan, plannerRevisionInstructions,
            previousExecutionResults, json);
    }

    @Transactional
    // 创建团队协作任务 参数1团队id 参数2任务描述
    public TeamRunSnapshot createRun(String teamId, String task) {
        return createRun(teamId, "default", task);
    }

    @Transactional
    // 创建团队协作任务 参数1团队id 参数2用户id 参数3任务描述
    public TeamRunSnapshot createRun(String teamId, String userId, String task) {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task is required");
        }
        // 根据团队id获取团队实体
        getTeam(teamId);
        // 创建团队协作任务实体
        TeamRunEntity run = new TeamRunEntity();
        run.setRunId(UUID.randomUUID().toString());
        run.setTeamId(teamId);
        run.setUserId(normalizeUserId(userId));
        run.setTask(task.trim());
        run.setStatus(TeamRunStatus.PENDING);
        runMapper.upsertById(run);
        recordEvent(run.getRunId(), null, TeamEventType.RUN_CREATED, null, null, "Run created", null);
        // Run 先落库，再在事务提交后异步执行，避免后台线程读到未提交数据。
        scheduleRun(run.getRunId());
        return getRun(teamId, userId, run.getRunId());
    }

    @Transactional(readOnly = true)
    // 根据团队id获取团队协作任务快照列表
    // 参数1团队id 参数2用户id
    // 返回值团队协作任务快照列表
    // 如果用户id为空或为空字符串，则返回团队id默认值
    public List<TeamRunSnapshot> listRuns(String teamId) {
        return listRuns(teamId, "default");
    }

    @Transactional(readOnly = true)
    // 根据团队id获取团队协作任务快照列表
    // 参数1团队id 参数2用户id
    // 返回值团队协作任务快照列表
    // 如果用户id为空或为空字符串，则返回团队id默认值
    public List<TeamRunSnapshot> listRuns(String teamId, String userId) {
        getTeam(teamId);
        return runMapper.selectByTeamIdAndUserIdOrderByCreatedAtDesc(teamId, normalizeUserId(userId)).stream()
            .map(run -> toRunSnapshot(run, true, false))
            .toList();
    }

    @Transactional(readOnly = true)
    // 根据用户id获取团队协作任务快照列表
    // 参数1用户id
    // 返回值团队协作任务快照列表
    // 如果用户id为空或为空字符串，则返回团队id默认值
    public List<TeamRunSnapshot> listRunsForUser(String userId) {
        return runMapper.selectByUserIdOrderByCreatedAtDesc(normalizeUserId(userId)).stream()
            .map(run -> toRunSnapshot(run, true, false))
            .toList();
    }

    @Transactional(readOnly = true)
    // 根据团队id获取团队协作任务快照
    // 参数1团队id 参数2用户id 参数3任务id
    // 返回值团队协作任务快照
    // 如果用户id为空或为空字符串，则返回团队id默认值
    public TeamRunSnapshot getRun(String teamId, String runId) {
        return getRun(teamId, "default", runId);
    }

    @Transactional(readOnly = true)
    // 根据团队id获取团队协作任务快照
    // 参数1团队id 参数2用户id 参数3任务id
    // 返回值团队协作任务快照
    // 如果用户id为空或为空字符串，则返回团队id默认值
    public TeamRunSnapshot getRun(String teamId, String userId, String runId) {
        TeamRunEntity run = loadRun(teamId, normalizeUserId(userId), runId);
        return toRunSnapshot(run, true, true);
    }

    @Transactional
    public TeamRunSnapshot resumeRun(String teamId, String runId, String clarificationAnswer) {
        return resumeRun(teamId, "default", runId, clarificationAnswer, null);
    }

    @Transactional
    public TeamRunSnapshot resumeRun(String teamId, String userId, String runId, String clarificationAnswer) {
        return resumeRun(teamId, userId, runId, clarificationAnswer, null);
    }

    @Transactional
    public TeamRunSnapshot resumeRun(String teamId, String userId, String runId, String clarificationAnswer,
                                     Map<String, String> stepClarificationAnswers) {
        TeamRunEntity run = loadRun(teamId, normalizeUserId(userId), runId);
        if (run.getStatus() != TeamRunStatus.NEEDS_CLARIFICATION) {
            throw new IllegalArgumentException("Run is not waiting for clarification: " + runId);
        }
        boolean hasRunAnswer = clarificationAnswer != null && !clarificationAnswer.isBlank();
        boolean hasStepAnswers = stepClarificationAnswers != null
            && stepClarificationAnswers.values().stream().anyMatch(v -> v != null && !v.isBlank());
        if (!hasRunAnswer && !hasStepAnswers) {
            throw new IllegalArgumentException("clarificationAnswer or stepClarificationAnswers is required");
        }
        if (hasRunAnswer) {
            run.setClarificationAnswer(clarificationAnswer.trim());
        } else {
            StringBuilder merged = new StringBuilder();
            for (Map.Entry<String, String> entry : stepClarificationAnswers.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    TeamStepEntity step = stepMapper.selectOptionalById(entry.getKey()).orElse(null);
                    String stepTitle = step != null ? step.getTitle() : entry.getKey();
                    merged.append("[").append(stepTitle).append("] ").append(entry.getValue().trim()).append("\n");
                }
            }
            run.setClarificationAnswer(merged.toString().trim());
        }
        if (run.getClarificationStage() == TeamClarificationStage.PLAN_REVIEW) {
            stepMapper.deleteByRunId(runId);
            run.setPlanReviewJson(null);
            run.setResultReviewJson(null);
            run.setFinalOutput(null);
            run.setMermaidDiagram(null);
            run.setPlanRetryCount(0);
        } else if (run.getClarificationStage() == TeamClarificationStage.RESULT_REVIEW) {
            if (hasStepAnswers) {
                for (Map.Entry<String, String> entry : stepClarificationAnswers.entrySet()) {
                    TeamStepEntity step = stepMapper.selectOptionalById(entry.getKey()).orElse(null);
                    if (step != null && entry.getValue() != null && !entry.getValue().isBlank()) {
                        String existing = step.getRevisionInstructions() == null ? "" : step.getRevisionInstructions();
                        step.setRevisionInstructions(existing + "\n用户澄清: " + entry.getValue().trim());
                        stepMapper.upsertById(step);
                    }
                }
            }
            run.setResultReviewJson(null);
            run.setFinalOutput(null);
            run.setMermaidDiagram(null);
            run.setResultReplanCount(0);
            run.setPartialReplanCount(0);
        }
        run.setClarificationQuestion(null);
        run.setClarificationStage(null);
        run.setStatus(TeamRunStatus.PENDING);
        runMapper.upsertById(run);
        recordEvent(runId, null, TeamEventType.RUN_RESUMED, null, null, "User clarification received", null);
        scheduleRun(runId);
        return getRun(teamId, userId, runId);
    }

    private void executeRunSafely(String runId) {
        try {
            executeRun(runId);
        } catch (Exception e) {
            log.error("Team run failed: {}", runId, e);
            failRun(runId, e.getMessage());
        }
    }

    /**
     * Team Run 的主执行入口
     *
     * 执行流程：
     * 1. 尝试原子性获取执行锁（防止并发重复执行）
     * 2. 检查是否超时
     * 3. 如果没有步骤，先规划
     * 4. 执行 DAG 步骤（SIMPLE/COMPLEX 统一走 DAG）
     * 5. 合并结果并终审
     *
     * @param runId 任务ID
     */
    @Transactional
    protected void executeRun(String runId) {
        // ============================================
        // 阶段1：尝试获取执行锁（原子性状态转换防止重复执行）
        // ============================================
        // 原子性地将状态从 PENDING -> EXECUTING
        // 如果失败，说明任务已被其他线程获取，跳过执行
        if (!runMapper.tryAcquireExecuteLock(runId)) {
            TeamRunEntity existingRun = runMapper.selectOptionalById(runId).orElse(null);
            if (existingRun == null) {
                log.warn("Run {} not found, skipping execution", runId);
                return;
            }
            
            // 根据当前状态判断是否需要跳过
            switch (existingRun.getStatus()) {
                case EXECUTING:
                    log.info("Run {} is already executing, skipping", runId);
                    break;
                case COMPLETED:
                    log.info("Run {} is already completed, skipping", runId);
                    break;
                case FAILED:
                    log.info("Run {} is already failed, skipping", runId);
                    break;
                case NEEDS_CLARIFICATION:
                    log.info("Run {} needs clarification, skipping", runId);
                    break;
                default:
                    log.warn("Run {} is in unexpected status: {}, skipping", runId, existingRun.getStatus());
            }
            return;
        }
        
        // 重新加载任务（因为 tryAcquireExecuteLock 已经更新了状态）
        TeamRunEntity run = runMapper.selectOptionalById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        
        // 加载团队快照
        TeamSnapshot team = getTeam(run.getTeamId());

        // Run 主入口：没有 Step 先规划；然后执行 DAG 步骤并合并终审。
        
        // 3. 检查是否超时
        if (failIfRunTimedOut(run)) {
            return;
        }
        
        // 4. 获取现有步骤
        List<TeamStepEntity> existingSteps = stepMapper.selectByRunIdOrderByStepIndexAsc(runId);
        if (existingSteps.isEmpty()) {
            // 没有步骤，需要先规划
            planAndReview(run, team);
            // 规划后检查状态：可能需要澄清或失败
            if (run.getStatus() == TeamRunStatus.NEEDS_CLARIFICATION || run.getStatus() == TeamRunStatus.FAILED) {
                return;
            }
        }

        // 5. 执行 DAG 步骤
        executeDagSteps(run, team);
        run = runMapper.selectOptionalById(runId).orElse(run);
        if (run.getStatus() == TeamRunStatus.FAILED || run.getStatus() == TeamRunStatus.NEEDS_CLARIFICATION
            || isRunDeleted(runId)) {
            return;
        }

        // 6. 合并结果并终审
        mergeAndReviewResults(run, team);
    }

    private void planAndReview(TeamRunEntity run, TeamSnapshot team) {
        planAndReview(run, team, List.of(), "", List.of());
    }

    private void planAndReview(TeamRunEntity run, TeamSnapshot team,
                               List<PlannedStep> initialPreviousPlan,
                               String initialRevisionInstructions,
                               List<TeamStepSnapshot> previousExecutionResults) {
        planAndReview(run, team, initialPreviousPlan, initialRevisionInstructions, previousExecutionResults, false);
    }

    private void planAndReview(TeamRunEntity run, TeamSnapshot team,
                               List<PlannedStep> initialPreviousPlan,
                               String initialRevisionInstructions,
                               List<TeamStepSnapshot> previousExecutionResults,
                               boolean preserveExistingSteps) {
        // Planner 和 Reviewer 的闭环：Reviewer 不通过时，把上一轮计划和修改意见带回 Planner。
        // 标记计划是否通过审核
        boolean approved = false;
        // 初始化计划：如果传入的 previousPlan 为 null，则使用空列表
        List<PlannedStep> previousPlan = initialPreviousPlan == null ? List.of() : initialPreviousPlan;
        // 初始化 Planner 的修改指令
        String plannerRevisionInstructions = nullToBlank(initialRevisionInstructions);

        // 循环直到计划通过审核或达到最大重试次数
        while (!approved && run.getPlanRetryCount() <= runtimeProperties.getMaxPlanRetries()) {
            // 1. 设置状态为 PLANNING，开始规划阶段
            run.setStatus(TeamRunStatus.PLANNING);
            runMapper.upsertById(run);
            // 记录规划开始事件
            recordEvent(run.getRunId(), null, TeamEventType.PLAN_STARTED, TeamRole.PLANNER,
                planner(team).agentId(), "Planner started task decomposition", null);

            // 2. 调用 Planner Agent 生成步骤计划
            // 参数：当前计划、上次修改意见、上次执行结果
            List<PlannedStep> planned = callPlanner(run, team, previousPlan, plannerRevisionInstructions,
                previousExecutionResults);
            requireRunWritable(run.getRunId());

            // 3. 用新计划替换现有步骤
            replaceSteps(run, planned, preserveExistingSteps);
            // 记录计划创建事件
            recordEvent(run.getRunId(), null, TeamEventType.PLAN_CREATED, TeamRole.PLANNER,
                planner(team).agentId(), "Planner created " + planned.size() + " step(s)", planned);

            // 4. 设置状态为 PLAN_REVIEWING，开始审核阶段
            run.setStatus(TeamRunStatus.PLAN_REVIEWING);
            runMapper.upsertById(run);
            // 记录审核开始事件
            recordEvent(run.getRunId(), null, TeamEventType.PLAN_REVIEW_STARTED, TeamRole.REVIEWER,
                reviewer(team).agentId(), "Reviewer started plan review", null);

            // 5. 调用 Reviewer Agent 审核计划
            ReviewerDecision decision = callReviewerForPlan(run, team, planned);
            // 确保数据库可写入
            requireRunWritable(run.getRunId());
            // 保存审核结果到数据库
            run.setPlanReviewJson(json.toJson(decision));
            runMapper.upsertById(run);
            // 记录审核完成事件
            recordEvent(run.getRunId(), null, TeamEventType.PLAN_REVIEWED, TeamRole.REVIEWER,
                reviewer(team).agentId(), decision.reason(), decision);

            // 6. 根据审核结果处理
            if (decision.action() == ReviewerAction.CONTINUE) {
                // 计划通过审核，退出循环
                approved = true;
            } else if (decision.action() == ReviewerAction.ASK_CLARIFICATION) {
                // 需要用户澄清，暂停任务
                pauseForClarification(run, decision, TeamClarificationStage.PLAN_REVIEW);
                return;
            } else if (run.getPlanRetryCount() >= runtimeProperties.getMaxPlanRetries()) {
                // 达到最大重试次数，任务失败
                failRun(run.getRunId(), "Planner retry limit reached: " + decision.reason());
                return;
            } else {
                // 计划需要修改，准备下一轮规划
                // 保存当前计划作为下一轮的参考
                previousPlan = planned;
                // 保存审核意见作为下一轮的修改指令
                plannerRevisionInstructions = decision.revisionInstructions();
                // 增加重试计数
                run.setPlanRetryCount(run.getPlanRetryCount() + 1);
                runMapper.upsertById(run);
                // 记录重试请求事件
                recordEvent(run.getRunId(), null, TeamEventType.RETRY_REQUESTED, TeamRole.REVIEWER,
                    reviewer(team).agentId(), "Reviewer requested planner retry: " + decision.reason(), decision);
            }
        }
    }

    private List<PlannedStep> callPlanner(TeamRunEntity run, TeamSnapshot team,
                                          List<PlannedStep> previousPlan,
                                          String plannerRevisionInstructions,
                                          List<TeamStepSnapshot> previousExecutionResults) {
        // 获取Planner成员
        TeamMember planner = planner(team);
        // 生成提示词
        String prompt = TeamPromptFactory.planner(run, team, previousPlan, plannerRevisionInstructions,
            previousExecutionResults, json);
        // 调用Planner Agent 生成步骤计划
        PipelineContext result = executeTeamControlInternal(planner.agentId(), plannerSession(run), prompt);
        String raw = result.getFinalResponse();
        // 解析步骤
        List<PlannedStep> steps = json.parsePlan(raw);
        if (steps.isEmpty()) {
            throw new IllegalStateException("Planner did not produce any steps");
        }
        run.setTaskLevel(json.parseTaskLevel(raw, steps.size()));
        runMapper.upsertById(run);
        return steps;
    }

    private ReviewerDecision callReviewerForPlan(TeamRunEntity run, TeamSnapshot team, List<PlannedStep> planned) {
        TeamMember reviewer = reviewer(team);
        return executeReviewerDecision(reviewer, run, TeamPromptFactory.planReviewer(run, planned, json), false);
    }

    /**
     * DAG 步骤执行循环
     * 
     * DAG 调度核心逻辑：
     * - 只认 Step 状态和 dependsOnStepIds
     * - READY 才执行
     * - BLOCKED 等依赖完成后放行
     * 
     * 执行流程：
     * 1. 设置状态为 EXECUTING
     * 2. 循环执行直到所有步骤完成或出现异常
     * 3. 每次循环：标记就绪步骤 → 并发执行 → 等待完成
     * 4. 支持超时检测、并发限制、失败检测
     * 
     * @param run 任务实体
     * @param team 团队快照
     */
    private void executeDagSteps(TeamRunEntity run, TeamSnapshot team) {
        // DAG 调度只认 Step 状态和 dependsOnStepIds：READY 才执行，BLOCKED 等依赖完成后放行。
        
        // 1. 设置任务状态为执行中
        run.setStatus(TeamRunStatus.EXECUTING);
        runMapper.upsertById(run);
        
        // 2. 记录 DAG 调度开始事件
        recordEvent(run.getRunId(), null, TeamEventType.TEAM_CONTROL_STARTED, null, null,
            "TeamControlCenter 启动 DAG 调度、并发限制、超时熔断和迭代拦截", Map.of(
                "maxConcurrentSteps", runtimeProperties.getMaxConcurrentSteps(),
                "maxStepRetries", runtimeProperties.getMaxStepRetries(),
                "stepTimeoutSeconds", runtimeProperties.getStepTimeoutSeconds(),
                "runTimeoutSeconds", runtimeProperties.getRunTimeoutSeconds()
            ));

        // 3. DAG 执行主循环
        while (true) {
            // 要检查删除没有删除,以防止用户删除team之后,却还是在DAG循环当中
            if (isRunDeleted(run.getRunId())) {
                return;
            }

            // 3.1 检查任务是否超时
            if (failIfRunTimedOut(run)) {
                return;
            }
            
            // 3.2 标记可以执行的步骤（READY 状态且依赖已满足）
            markReadySteps(run); 
            
            // 3.3 查询就绪的步骤（最多并发执行的数量）
            List<TeamStepEntity> readySteps = stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
                .filter(step -> step.getStatus() == TeamStepStatus.READY)
                .limit(runtimeProperties.getMaxConcurrentSteps())
                .toList();

            // 3.4 存在失败步骤时立即终止，避免无谓执行
            if (stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
                .anyMatch(step -> step.getStatus() == TeamStepStatus.FAILED)) {
                failRun(run.getRunId(), "存在失败 Step，DAG 停止执行");
                return;
            }

            // 3.5 没有可执行的步骤 -> 判断原因
            if (readySteps.isEmpty()) {
                List<TeamStepEntity> all = stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId());

                // 情况1：有步骤正在执行，等待后重试
                if (all.stream().anyMatch(step -> step.getStatus() == TeamStepStatus.RUNNING)) {
                    log.debug("[DAG] Steps still executing for run {}, waiting {}ms", run.getRunId(),
                        runtimeProperties.getStepPollIntervalMs());
                    try {
                        Thread.sleep(runtimeProperties.getStepPollIntervalMs());
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    continue;
                }
                
                // 情况3：所有步骤都完成，正常退出
                boolean allDone = all.stream().allMatch(step ->
                    step.getStatus() == TeamStepStatus.COMPLETED || step.getStatus() == TeamStepStatus.SUPERSEDED);
                if (allDone) {
                    return;
                }
                
                // 情况4：依赖无法继续，标记失败
                failRun(run.getRunId(), "DAG 依赖无法继续推进，请检查 Planner 输出的 dependsOn");
                return;
            }

            // 3.6 并发执行就绪的步骤
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (TeamStepEntity step : readySteps) {
                // 使用线程池异步执行步骤
                CompletableFuture<Void> future = CompletableFuture
                    .runAsync(() -> executeStepSafely(run.getRunId(), step.getStepId()), taskExecutor);
                
                // 设置步骤超时
                if (runtimeProperties.getStepTimeoutSeconds() > 0) {
                    future = future.orTimeout(runtimeProperties.getStepTimeoutSeconds(), TimeUnit.SECONDS);
                }
                
                // 添加异常处理
                futures.add(future.exceptionally(error -> {
                    handleStepFutureFailure(run.getRunId(), step.getStepId(), error);
                    return null;
                }));
            }
            
            // 3.7 等待所有步骤完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            // 3.8 检查是否应该停止（pauseForClarification / failRun 设置的内存标记，
            //     防止用户在原线程退出前 resumeRun 导致两个线程同时执行）
            if (stoppingRunIds.remove(run.getRunId())) {
                return;
            }

            // 3.9 子线程可能标记了 Step FAILED，优先于澄清处理
            if (stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
                .anyMatch(step -> step.getStatus() == TeamStepStatus.FAILED)) {
                failRun(run.getRunId(), "存在失败 Step，DAG 停止执行");
                return;
            }

            // 3.10 子线程收集的澄清请求：主线程统一合并后一次性暂停，
            //      解决多个并行 Step 同时要求澄清时前端竞态问题
            String clarificationQuestion = pendingClarifications.remove(run.getRunId());
            if (clarificationQuestion != null) {
                stoppingRunIds.add(run.getRunId());
                run.setClarificationQuestion(clarificationQuestion);
                run.setClarificationStage(TeamClarificationStage.RESULT_REVIEW);
                run.setStatus(TeamRunStatus.NEEDS_CLARIFICATION);
                runMapper.upsertById(run);
                recordEvent(run.getRunId(), null, TeamEventType.CLARIFICATION_REQUESTED, TeamRole.REVIEWER,
                    null, clarificationQuestion, null);
                return;
            }

            // 3.11 检查最新状态
            TeamRunEntity latest = runMapper.selectOptionalById(run.getRunId()).orElse(run);
            run.setStatus(latest.getStatus());
            
            // 3.10 检查是否需要暂停或失败
            if (latest.getStatus() == TeamRunStatus.NEEDS_CLARIFICATION || latest.getStatus() == TeamRunStatus.FAILED) {
                return;
            }
        }
    }

    /**
     * 标记可执行的步骤（DAG 依赖解析）
     * 
     * 核心逻辑：
     * 1. 收集所有已完成的步骤 ID
     * 2. 遍历所有步骤，检查依赖是否满足
     * 3. 依赖满足的步骤标记为 READY
     * 4. 依赖未满足的 PENDING 步骤标记为 BLOCKED
     * 
     * 状态转换规则：
     * - PENDING → READY（依赖满足）
     * - PENDING → BLOCKED（依赖未满足）
     * - BLOCKED → READY（依赖满足）
     * - RETRYING → READY（依赖满足）
     * 
     * @param run 任务实体
     */
    private void markReadySteps(TeamRunEntity run) {
        // 1. 获取所有步骤
        List<TeamStepEntity> all = stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId());
        
        // 2. 收集已完成的步骤 ID（COMPLETED 或 SUPERSEDED 都视为完成）
        Set<String> completed = new HashSet<>();
        for (TeamStepEntity step : all) {
            if (step.getStatus() == TeamStepStatus.COMPLETED || step.getStatus() == TeamStepStatus.SUPERSEDED) {
                completed.add(step.getStepId());
            }
        }
        
        // 3. 遍历步骤，检查依赖是否满足
        for (TeamStepEntity step : all) {
            // PENDING 首次发现依赖未完成会标记 BLOCKED；依赖满足后统一转 READY。
            // 只处理需要检查依赖的步骤
            if (step.getStatus() != TeamStepStatus.PENDING
                && step.getStatus() != TeamStepStatus.BLOCKED
                && step.getStatus() != TeamStepStatus.RETRYING) {
                continue;
            }
            
            // 获取当前步骤的依赖列表
            List<String> dependencies = json.stringList(step.getDependsOnStepIdsJson());
            
            // 4. 检查依赖是否都已完成
            if (completed.containsAll(dependencies)) {
                // 依赖满足，标记为 READY
                step.setStatus(TeamStepStatus.READY);
                stepMapper.upsertById(step);
                recordEvent(run.getRunId(), step.getStepId(), TeamEventType.STEP_READY, null, null,
                    "Step 依赖已完成，进入可执行队列", Map.of("dependsOnStepIds", dependencies));
            } else if (step.getStatus() == TeamStepStatus.PENDING) {
                // 依赖未满足，标记为 BLOCKED（仅 PENDING 状态需要标记，BLOCKED 保持不变）
                step.setStatus(TeamStepStatus.BLOCKED);
                stepMapper.upsertById(step);
                recordEvent(run.getRunId(), step.getStepId(), TeamEventType.STEP_BLOCKED, null, null,
                    "Step 等待前置依赖完成", Map.of("dependsOnStepIds", dependencies));
            }
        }
    }

    private void executeStepSafely(String runId, String stepId) {
        try {
            executeStep(runId, stepId);
        } catch (Exception e) {
            if (isRunDeleted(runId)) {
                return;
            }
            log.error("Team step failed: {}", stepId, e);
            markStepFailed(runId, stepId, e.getMessage());
        }
    }

    private void handleStepFutureFailure(String runId, String stepId, Throwable error) {
        if (isRunDeleted(runId)) {
            return;
        }
        Throwable root = unwrapCompletion(error);
        if (root instanceof TimeoutException) {
            markStepTimedOut(runId, stepId);
            return;
        }
        markStepFailed(runId, stepId, root.getMessage());
    }

    private Throwable unwrapCompletion(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
            && current.getCause() != null) {
            current = current.getCause();
        }
        return current == null ? new IllegalStateException("unknown async failure") : current;
    }

    /**
     * 执行单个 Step 的完整生命周期。
     * <p>这是 Team 任务执行的核心方法，负责协调单个步骤的完整执行流程：
     * <ol>
     *   <li><strong>加载实体</strong>：从数据库获取 Run、Team、Step 实体</li>
     *   <li><strong>选择 Executor</strong>：基于能力匹配 + 模型智能选择合适的执行 Agent</li>
     *   <li><strong>状态管理</strong>：更新步骤状态为 ASSIGNED → RUNNING</li>
     *   <li><strong>构建提示词</strong>：生成包含依赖步骤输出的执行提示词</li>
     *   <li><strong>执行 Agent</strong>：调用选定的 Executor Agent 执行步骤</li>
     *   <li><strong>结果处理</strong>：保存执行结果，处理高风险步骤的 SubReview</li>
     * </ol>
     *
     * <p><strong>状态流转：</strong>
     * <br/>PENDING/BLOCKED/RETRYING → ASSIGNED → RUNNING → COMPLETED/FAILED
     *
     * <p><strong>异常处理：</strong>
     * <br/>执行过程中发生异常时，会将步骤状态设置为 FAILED，确保 DAG 调度能正确识别并处理失败。
     *
     * @param runId  任务运行 ID，用于关联到具体的 Team 任务
     * @param stepId 步骤 ID，标识要执行的具体步骤
     */
    @Transactional
    protected void executeStep(String runId, String stepId) {
        // ============================================
        // 阶段1：加载实体
        // ============================================
        // 从数据库获取任务运行实体，如果不存在则抛出异常
        TeamRunEntity run = runMapper.selectOptionalById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        
        // 获取团队快照，包含团队成员和配置信息
        TeamSnapshot team = getTeam(run.getTeamId());
        
        // 获取步骤实体，如果不存在则抛出异常
        TeamStepEntity step = stepMapper.selectOptionalById(stepId)
            .orElseThrow(() -> new IllegalArgumentException("Step not found: " + stepId));

        // ============================================
        // 阶段2：选择 Executor Agent
        // ============================================
        // 解析步骤所需的能力列表
        List<String> requiredCapabilities = json.stringList(step.getRequiredCapabilitiesJson());
        
        // 调用选择器选择 Executor（模型智能选择 + 规则兜底）
        // selectExecutorWithModel 会先通过规则引擎筛选候选，再让 Planner 模型做最终选择
        TeamAgentSelection selection = selectExecutorWithModel(run, team, step, requiredCapabilities);
        
        // 根据选择结果获取对应的 Agent 成员
        TeamMember executor = selection == null ? null : executorBySelection(team, selection);
        
        // 如果没有找到合适的 Executor，抛出异常终止执行
        if (executor == null) {
            throw new IllegalStateException("No executor configured");
        }
        
        // 记录 Agent 选择事件（selection 可能为 null，需做空值保护）
        recordEvent(runId, stepId, TeamEventType.AGENT_SELECTED, TeamRole.PLANNER, planner(team).agentId(),
            "模型选择 Executor " + executor.agentName() + "：" + (selection != null ? selection.reason() : "规则兜底"), selection);

        // ============================================
        // 阶段3：设置步骤状态（区分重试和首次执行）
        // ============================================
        // 判断是否为重试：重试次数 > 0 或当前状态为 RETRYING
        boolean retrying = step.getRetryCount() > 0 || step.getStatus() == TeamStepStatus.RETRYING;
        
        // 设置分配的 Agent ID
        step.setAssignedAgentId(executor.agentId());
        step.setStatus(retrying ? TeamStepStatus.RETRYING : TeamStepStatus.ASSIGNED);
        step.setStartedAt(Instant.now());
        step.setSubReviewJson(null);
        stepMapper.upsertById(step);
        
        // 记录步骤分配事件
        recordEvent(runId, stepId, TeamEventType.STEP_ASSIGNED, TeamRole.EXECUTOR, executor.agentId(),
            "Step assigned to " + executor.agentName(), Map.of(
                "requiredCapabilities", requiredCapabilities,
                "selection", selection
            ));

        // ============================================
        // 阶段4：标记步骤开始执行
        // ============================================
        // 更新状态为 RUNNING（重试时仍为 RETRYING，便于区分）
        step.setStatus(retrying ? TeamStepStatus.RETRYING : TeamStepStatus.RUNNING);
        stepMapper.upsertById(step);
        
        // 记录步骤开始事件（区分首次执行和重试）
        recordEvent(runId, stepId, retrying ? TeamEventType.STEP_RETRY_STARTED : TeamEventType.STEP_STARTED,
            TeamRole.EXECUTOR, executor.agentId(), "Executor started step", null);

        // ============================================
        // 阶段5：构建执行提示词
        // ============================================
        // 使用 TeamPromptFactory 生成 Executor 的执行提示词
        // 提示词包含：用户任务、步骤描述、依赖步骤的输出
        String prompt = TeamPromptFactory.executor(run, step, dependencyOutputs(step));
        
        // 保存输入信息到步骤实体（便于追踪和调试）
        step.setInputJson(json.toJson(Map.of("prompt", prompt, "selection", selection)));
        stepMapper.upsertById(step);

        // ============================================
        // 阶段6：调用 Agent 执行
        // ============================================
        String output;
        try {
            // 调用 Agent 执行步骤
            // executeTeamInternal 会执行完整的 Agent Pipeline（记忆加载、工具调用等）
            PipelineContext result = executeTeamInternal(executor.agentId(), stepSession(run, step), prompt);
            
            // 处理可能的 null 返回值（防止 NPE）
            output = result != null ? result.getFinalResponse() : null;
            
            // 如果 Agent 返回错误标记，抛出异常
            if (output != null && output.startsWith("[Error]")) {
                throw new IllegalStateException(output);
            }
        } catch (Exception e) {
            // 执行失败时设置失败状态，避免步骤停留在 RUNNING 导致 DAG 卡住
            step.setStatus(TeamStepStatus.FAILED);
            step.setRawOutput("Step failed: " + e.getMessage());
            stepMapper.upsertById(step);
            throw e;  // 继续向上抛出异常，让上层处理
        }

        // ============================================
        // 阶段7：保存执行结果
        // ============================================
        // 检查任务是否可写（用于并发控制）
        requireRunWritable(runId); // 这个是防止用户在正在跑的时候直接删除team就浪费资源了
        
        // 保存 Agent 的输出结果
        step.setRawOutput(output);

        // ============================================
        // 阶段8：处理结果（高风险步骤 vs 普通步骤）
        // ============================================
        // 如果是高风险步骤，需要经过 SubReview 审核
        if (step.getRiskLevel() == TeamRiskLevel.HIGH) {
            reviewHighRiskStep(run, team, step, retrying);
            return;
        }
        completeStep(runId, step, executor.agentId(), retrying, TeamStepQualityStatus.PASSED);
    }

    /**
     * 选择执行步骤的 Executor Agent。
     * <p>采用"模型主选 + 规则兜底"策略：
     * <ol>
     *   <li>首先通过规则引擎获取候选 Executor 列表（按能力匹配度排序）</li>
     *   <li>调用 Planner 模型进行智能选择（考虑步骤语义、依赖输出等）</li>
     *   <li>模型选择有效时返回模型选择结果</li>
     *   <li>模型选择无效或异常时，使用规则兜底（选择候选列表首位）</li>
     * </ol>
     *
     * @param run                  任务运行实体
     * @param team                 团队快照
     * @param step                 步骤实体
     * @param requiredCapabilities 步骤需要的能力列表
     * @return 选中的 Executor Agent，若无候选则返回 null
     */
    private TeamAgentSelection selectExecutorWithModel(TeamRunEntity run, TeamSnapshot team, TeamStepEntity step,
                                                       List<String> requiredCapabilities) {
        // 1. 获取候选 Executor 列表（按能力匹配度排序）
        List<TeamStepEntity> runSteps = stepMapper == null
            ? List.of()
            : stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId());
        List<TeamAgentSelection> candidates = agentSelector.rankExecutors(team.members(), requiredCapabilities,
            runSteps);
        if (candidates.isEmpty()) {
            return null;  // 无候选 Executor
        }

        // 2. 创建规则兜底选择（候选列表首位）
        TeamAgentSelection fallback = candidates.get(0).withDecision("RULE_FALLBACK",
            "模型选择不可用时的规则兜底：" + candidates.get(0).reason());

        // 3. 调用 Planner 模型进行智能选择
        // 主选择权交给模型；模型输出无效或异常时，退回能力匹配分 + sortOrder 的稳定兜底
        TeamMember planner = planner(team);
        String prompt = TeamPromptFactory.agentSelector(run, step, requiredCapabilities, dependencyOutputs(step),
            candidates, json);

        try {
            // 4. 执行模型选择
            PipelineContext result = executeTeamControlInternal(planner.agentId(),
                "team-run-" + run.getRunId() + "-agent-selector-" + step.getStepId() + "-" + UUID.randomUUID(),
                prompt);

            // 5. 解析模型决策并匹配候选列表
            TeamExecutorSelectionDecision decision = json.parseExecutorSelectionDecision(
                result == null ? "" : result.getFinalResponse());
            TeamAgentSelection selected = agentSelector.matchModelDecision(candidates, decision.memberKey(),
                decision.agentId());

            // 6. 返回模型选择或兜底选择
            if (selected != null) {
                return selected.withDecision("MODEL",
                    "模型自主选择：" + blankToDefault(decision.reason(), "匹配当前 Step 的语义和能力要求")
                        + "；" + selected.reason());
            }
            // 模型返回的 Executor 不在候选列表中，使用规则兜底
            return fallback.withDecision("RULE_FALLBACK",
                "模型返回的 Executor 不在候选列表中，使用规则兜底；模型原始选择 memberKey="
                    + decision.memberKey() + "，agentId=" + decision.agentId() + "；" + fallback.reason());
        } catch (Exception e) {
            // 模型选择失败，使用规则兜底
            log.warn("AgentSelector model decision failed for run {} step {}: {}", run.getRunId(), step.getStepId(),
                e.getMessage());
            return fallback.withDecision("RULE_FALLBACK",
                "模型选择失败，使用规则兜底：" + e.getMessage() + "；" + fallback.reason());
        }
    }

    private void reviewHighRiskStep(TeamRunEntity run, TeamSnapshot team, TeamStepEntity step, boolean retrying) {
        // 获取reviewer成员
        TeamMember reviewer = subReviewer(team);
        // 事件
        recordEvent(run.getRunId(), step.getStepId(), TeamEventType.STEP_SUB_REVIEW_STARTED, TeamRole.SUB_REVIEWER,
            reviewer.agentId(), "SubReviewer 开始校验高风险 Step", null);

        // 调用 SubReviewer 审查 Step 输出
        ReviewerDecision decision = callSubReviewer(run, reviewer, step);
        step.setSubReviewJson(json.toJson(decision));
        step.setLastReviewReason(decision.reason());
        recordEvent(run.getRunId(), step.getStepId(), TeamEventType.STEP_SUB_REVIEWED, TeamRole.SUB_REVIEWER,
            reviewer.agentId(), decision.reason(), decision);

        if (decision.action() == ReviewerAction.CONTINUE) {
            // 审查通过，标记完成
            completeStep(run.getRunId(), step, step.getAssignedAgentId(), retrying, TeamStepQualityStatus.PASSED);
            return;
        }
        if (decision.action() == ReviewerAction.ASK_CLARIFICATION) {
            step.setStatus(TeamStepStatus.PENDING);
            step.setRevisionInstructions("SubReviewer requested clarification: " + decision.reason());
            stepMapper.upsertById(step);
            String question = decision.questions().isEmpty()
                ? decision.reason()
                : String.join("\n", decision.questions());
            pendingClarifications.merge(run.getRunId(), question,
                (existing, newQ) -> existing + "\n\n" + newQ);
            return;
        }
        if (step.getRetryCount() >= runtimeProperties.getMaxStepRetries()) {
            // 重试次数耗尽，标记为 FLAWED_ACCEPTED（有缺陷但接受），允许继续推进
            step.setQualityStatus(TeamStepQualityStatus.FLAWED_ACCEPTED);
            step.setReviewStatus("FLAWED_ACCEPTED");
            step.setReflectionJson(json.toJson(reflectionForStep(step, decision)));
            completeStep(run.getRunId(), step, step.getAssignedAgentId(), retrying, TeamStepQualityStatus.FLAWED_ACCEPTED);
            return;
        }
        // 重试次数未耗尽，写入 Reflexion 上下文后重新执行
        prepareStepRetry(run, step, decision, reviewer);
    }

    private ReviewerDecision callSubReviewer(TeamRunEntity run, TeamMember reviewer, TeamStepEntity step) {
        return executeReviewerDecision(reviewer, run, TeamPromptFactory.subReviewer(run, step), false);
    }

    private void completeStep(String runId, TeamStepEntity step, String actorAgentId, boolean retrying,
                              TeamStepQualityStatus qualityStatus) {
        if (isRunDeleted(runId)) {
            return;
        }
        step.setQualityStatus(qualityStatus);
        step.setReviewStatus(qualityStatus.name());
        step.setStatus(TeamStepStatus.COMPLETED);
        step.setCompletedAt(Instant.now());
        stepMapper.upsertById(step);
        recordEvent(runId, step.getStepId(), retrying ? TeamEventType.STEP_RETRY_COMPLETED : TeamEventType.STEP_COMPLETED,
            TeamRole.EXECUTOR, actorAgentId, "Executor 完成 Step", Map.of("qualityStatus", qualityStatus.name()));
    }

    private void mergeAndReviewResults(TeamRunEntity run, TeamSnapshot team) {
        // 固定结果链路：MergeAgent 聚合 -> ConflictDetector 查冲突 -> 必要时 Planner 仲裁 -> GlobalReviewer 终审。
        run.setStatus(TeamRunStatus.MERGING);
        runMapper.upsertById(run);
        TeamMember merger = merger(team);
        recordEvent(run.getRunId(), null, TeamEventType.MERGE_STARTED, TeamRole.MERGER,
            merger.agentId(), "MergeAgent 开始聚合 Step 输出", null);
        String mergeOutput = callMerger(run, merger);
        run.setMergeOutput(mergeOutput);
        runMapper.upsertById(run);
        recordEvent(run.getRunId(), null, TeamEventType.MERGE_COMPLETED, TeamRole.MERGER,
            merger.agentId(), "MergeAgent 完成聚合", Map.of("mergeOutput", mergeOutput));
        TeamConflictReport conflictReport = detectConflicts(run, team, mergeOutput);
        run.setConflictReportJson(json.toJson(conflictReport));
        runMapper.upsertById(run);
        recordEvent(run.getRunId(), null, TeamEventType.CONFLICT_DETECTED, null, null,
            conflictReport.hasConflict() ? "ConflictDetector 发现冲突" : "ConflictDetector 未发现冲突", conflictReport);
        if (conflictReport.hasConflict()
            && run.getArbitrationCount() < runtimeProperties.getMaxArbitrations()) {
            String arbitration = arbitrateConflicts(run, team, conflictReport);
            run.setArbitrationCount(run.getArbitrationCount() + 1);
            run.setArbitrationJson(json.toJson(Map.of(
                "arbitration", arbitration,
                "conflictReport", conflictReport,
                "arbitrationCount", run.getArbitrationCount()
            )));
            runMapper.upsertById(run);
            recordEvent(run.getRunId(), null, TeamEventType.ARBITRATION_COMPLETED, TeamRole.PLANNER,
                planner(team).agentId(), "Planner 完成冲突仲裁", run.getArbitrationJson());
            recordEvent(run.getRunId(), null, TeamEventType.MERGE_STARTED, TeamRole.MERGER,
                merger.agentId(), "MergeAgent 带 Planner 仲裁结果二次聚合", null);
            mergeOutput = callMerger(run, merger);
            run.setMergeOutput(mergeOutput);
            TeamConflictReport secondReport = detectConflicts(run, team, mergeOutput);
            run.setConflictReportJson(json.toJson(secondReport));
            runMapper.upsertById(run);
            recordEvent(run.getRunId(), null, TeamEventType.MERGE_COMPLETED, TeamRole.MERGER,
                merger.agentId(), "MergeAgent 完成仲裁后二次聚合", Map.of("mergeOutput", mergeOutput));
            recordEvent(run.getRunId(), null, TeamEventType.CONFLICT_DETECTED, null, null,
                secondReport.hasConflict() ? "ConflictDetector 二次检测仍有冲突" : "ConflictDetector 二次检测无冲突", secondReport);
        }
        reviewResults(run, team);
    }

    private String callMerger(TeamRunEntity run, TeamMember merger) {
        List<TeamStepSnapshot> steps = stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
            .map(this::toStepSnapshot)
            .toList();
        String prompt = TeamPromptFactory.merger(run, steps, json);
        PipelineContext result = executeTeamControlInternal(merger.agentId(), "team-run-" + run.getRunId() + "-merger", prompt);
        String output = result == null ? "" : result.getFinalResponse();
        return output == null || output.isBlank() ? synthesizeFallbackReport(run) : output;
    }

    private TeamConflictReport detectConflicts(TeamRunEntity run, TeamSnapshot team, String mergeOutput) {
        TeamMember reviewer = reviewer(team);
        List<TeamStepSnapshot> steps = stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
            .map(this::toStepSnapshot)
            .toList();
        String prompt = TeamPromptFactory.conflictDetector(run, steps, mergeOutput, json);
        try {
            PipelineContext result = executeTeamControlInternal(reviewer.agentId(),
                "team-run-" + run.getRunId() + "-conflict-detector-" + UUID.randomUUID(), prompt);
            TeamConflictReport report = json.parseConflictReport(result == null ? "" : result.getFinalResponse());
            if (!report.hasConflict()) {
                return TeamConflictReport.none(blankToDefault(report.reason(), "未发现明显冲突"));
            }
            return report;
        } catch (Exception e) {
            log.warn("ConflictDetector failed for run {}: {}", run.getRunId(), e.getMessage());
            return TeamConflictReport.none("ConflictDetector 未能解析结构化结果，交由 GlobalReviewer 兜底终审");
        }
    }

    private String arbitrateConflicts(TeamRunEntity run, TeamSnapshot team, TeamConflictReport conflictReport) {
        TeamMember planner = planner(team);
        recordEvent(run.getRunId(), null, TeamEventType.ARBITRATION_STARTED, TeamRole.PLANNER,
            planner.agentId(), "Planner 开始冲突仲裁", conflictReport);
        List<TeamStepSnapshot> steps = stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
            .map(this::toStepSnapshot)
            .toList();
        String prompt = TeamPromptFactory.arbitration(run, conflictReport, steps, json);
        PipelineContext result = executeTeamControlInternal(planner.agentId(),
            "team-run-" + run.getRunId() + "-planner-arbitration-" + UUID.randomUUID(), prompt);
        String output = result == null ? "" : result.getFinalResponse();
        return output == null || output.isBlank() ? "未生成仲裁意见，GlobalReviewer 继续兜底终审。" : output;
    }

    private void reviewResults(TeamRunEntity run, TeamSnapshot team) {
        // GlobalReviewer 是最终质量闸门：可以通过、重试 Step、局部重规划、整体重规划、澄清或失败。
        run.setStatus(TeamRunStatus.GLOBAL_REVIEWING);
        runMapper.upsertById(run);
        recordEvent(run.getRunId(), null, TeamEventType.GLOBAL_REVIEW_STARTED, TeamRole.REVIEWER,
            reviewer(team).agentId(), "GlobalReviewer 开始终审", null);

        ReviewerDecision decision = callReviewerForResults(run, team);
        requireRunWritable(run.getRunId());
        run.setResultReviewJson(json.toJson(decision));
        run.setGlobalReviewJson(json.toJson(decision));
        runMapper.upsertById(run);
        recordEvent(run.getRunId(), null, TeamEventType.GLOBAL_REVIEWED, TeamRole.REVIEWER,
            reviewer(team).agentId(), decision.reason(), decision);

        if (decision.action() == ReviewerAction.CONTINUE) {
            run.setFinalOutput(decision.finalReport().isBlank() ? synthesizeFallbackReport(run) : decision.finalReport());
            run.setStatus(TeamRunStatus.COMPLETED);
            run.setMermaidDiagram(MermaidGenerator.generateFromSnapshots(
                getTeam(run.getTeamId()).name(),
                TeamRunStatus.COMPLETED,
                run.getTaskLevel() == null ? TeamTaskLevel.COMPLEX.name() : run.getTaskLevel().name(),
                toRunSnapshot(run, true, true).steps(),
                toRunSnapshot(run, true, true).events()
            ));
            runMapper.upsertById(run);
            recordEvent(run.getRunId(), null, TeamEventType.RUN_COMPLETED, TeamRole.REVIEWER,
                reviewer(team).agentId(), "Reviewer completed final report", null);
            return;
        }

        if (decision.action() == ReviewerAction.ASK_CLARIFICATION) {
            pauseForClarification(run, decision, TeamClarificationStage.RESULT_REVIEW);
            return;
        }

        if (decision.action() == ReviewerAction.REPLAN) {
            replanFromResultReview(run, team, decision);
            return;
        }

        if (decision.action() == ReviewerAction.PARTIAL_REPLAN) {
            partialReplanFromResultReview(run, team, decision);
            return;
        }

        if (decision.action() == ReviewerAction.FAILED) {
            failRun(run.getRunId(), "GlobalReviewer 判定失败: " + decision.reason());
            return;
        }

        List<TeamStepEntity> retrySteps = retryableSteps(run.getRunId(), decision.retryStepIds());
        if (retrySteps.isEmpty()) {
            failRun(run.getRunId(), "Reviewer requested retry but no retryable steps remained: " + decision.reason());
            return;
        }
        for (TeamStepEntity step : retrySteps) {
            prepareStepRetry(run, step, decision, reviewer(team));
        }
        executeDagSteps(run, team);
        if (run.getStatus() == TeamRunStatus.NEEDS_CLARIFICATION || run.getStatus() == TeamRunStatus.FAILED) {
            return;
        }
        mergeAndReviewResults(run, team);
    }

    private void replanFromResultReview(TeamRunEntity run, TeamSnapshot team, ReviewerDecision decision) {
        // 整体重规划会保留旧 Step 作为历史，将新计划作为下一轮完整 DAG。
        if (run.getResultReplanCount() >= runtimeProperties.getMaxResultReplans()) {
            failRun(run.getRunId(), "Reviewer requested replan but result replan limit reached: " + decision.reason());
            return;
        }

        List<TeamStepSnapshot> previousResults = stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
            .map(this::toStepSnapshot)
            .toList();
        List<PlannedStep> previousPlan = previousResults.stream()
            .map(step -> new PlannedStep(
                step.clientStepId(),
                step.title(),
                step.description(),
                step.requiredCapabilities(),
                step.acceptanceCriteria(),
                step.dependsOnStepIds(),
                step.riskLevel(),
                step.riskReason()
            ))
            .toList();

        run.setResultReplanCount(run.getResultReplanCount() + 1);
        run.setFullReplanCount(run.getFullReplanCount() + 1);
        run.setPlanRetryCount(0);
        run.setResultReviewJson(null);
        run.setGlobalReviewJson(null);
        run.setMergeOutput(null);
        run.setConflictReportJson(null);
        run.setArbitrationJson(null);
        run.setArbitrationCount(0);
        run.setFinalOutput(null);
        run.setMermaidDiagram(null);
        runMapper.upsertById(run);
        recordEvent(run.getRunId(), null, TeamEventType.REPLAN_REQUESTED, TeamRole.REVIEWER,
            reviewer(team).agentId(), "Reviewer requested result replan: " + decision.reason(), decision);

        planAndReview(run, team, previousPlan, decision.revisionInstructions(), previousResults, true);
        if (run.getStatus() == TeamRunStatus.NEEDS_CLARIFICATION || run.getStatus() == TeamRunStatus.FAILED) {
            return;
        }

        executeDagSteps(run, team);
        if (run.getStatus() == TeamRunStatus.FAILED) {
            return;
        }
        mergeAndReviewResults(run, team);
    }

    private void partialReplanFromResultReview(TeamRunEntity run, TeamSnapshot team, ReviewerDecision decision) {
        // 局部重规划只替换受影响分支及其下游，未受影响的已完成 Step 继续作为依赖可复用。
        if (run.getPartialReplanCount() >= runtimeProperties.getMaxResultReplans()) {
            failRun(run.getRunId(), "Reviewer requested partial replan but partial replan limit reached: " + decision.reason());
            return;
        }
        List<TeamStepEntity> all = stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId());
        Set<String> roots = resolveStepIds(all, decision.affectedStepIds().isEmpty()
            ? decision.retryStepIds()
            : decision.affectedStepIds());
        if (roots.isEmpty()) {
            failRun(run.getRunId(), "Reviewer requested partial replan but affected steps were not found: " + decision.reason());
            return;
        }
        Set<String> affected = expandWithDownstream(all, roots);
        List<TeamStepSnapshot> previousResults = all.stream()
            .map(this::toStepSnapshot)
            .toList();
        List<PlannedStep> affectedPlan = all.stream()
            .filter(step -> affected.contains(step.getStepId()))
            .map(step -> new PlannedStep(
                step.getClientStepId(),
                step.getTitle(),
                step.getDescription(),
                json.stringList(step.getRequiredCapabilitiesJson()),
                step.getAcceptanceCriteria(),
                json.stringList(step.getDependsOnStepIdsJson()),
                step.getRiskLevel(),
                step.getRiskReason()
            ))
            .toList();

        run.setPartialReplanCount(run.getPartialReplanCount() + 1);
        run.setPlanRetryCount(0);
        run.setResultReviewJson(null);
        run.setGlobalReviewJson(null);
        run.setMergeOutput(null);
        run.setConflictReportJson(null);
        run.setArbitrationJson(null);
        run.setFinalOutput(null);
        run.setMermaidDiagram(null);
        runMapper.upsertById(run);
        recordEvent(run.getRunId(), null, TeamEventType.REPLAN_REQUESTED, TeamRole.REVIEWER,
            reviewer(team).agentId(), "GlobalReviewer requested partial DAG replan: " + decision.reason(), decision);

        for (TeamStepEntity step : all) {
            if (affected.contains(step.getStepId())) {
                step.setStatus(TeamStepStatus.SUPERSEDED);
                step.setRevisionInstructions(decision.revisionInstructions());
                step.setLastReviewReason(decision.reason());
                step.setReflectionJson(json.toJson(reflectionForStep(step, decision)));
                stepMapper.upsertById(step);
                recordEvent(run.getRunId(), step.getStepId(), TeamEventType.STEP_REFLECTION_RECORDED,
                    TeamRole.REVIEWER, reviewer(team).agentId(), "局部重规划写入 Reflexion 上下文", step.getReflectionJson());
            }
        }

        run.setStatus(TeamRunStatus.PLANNING);
        runMapper.upsertById(run);
        recordEvent(run.getRunId(), null, TeamEventType.PLAN_STARTED, TeamRole.PLANNER,
            planner(team).agentId(), "Planner started partial DAG replan", Map.of("affectedStepIds", affected));
        List<PlannedStep> planned = callPlanner(run, team, affectedPlan, decision.revisionInstructions(), previousResults);
        appendPartialReplannedSteps(run, planned);
        recordEvent(run.getRunId(), null, TeamEventType.PLAN_CREATED, TeamRole.PLANNER,
            planner(team).agentId(), "Planner created " + planned.size() + " replacement step(s)", planned);

        run.setStatus(TeamRunStatus.PLAN_REVIEWING);
        runMapper.upsertById(run);
        ReviewerDecision planDecision = callReviewerForPlan(run, team, planned);
        run.setPlanReviewJson(json.toJson(planDecision));
        runMapper.upsertById(run);
        recordEvent(run.getRunId(), null, TeamEventType.PLAN_REVIEWED, TeamRole.REVIEWER,
            reviewer(team).agentId(), planDecision.reason(), planDecision);
        if (planDecision.action() == ReviewerAction.ASK_CLARIFICATION) {
            pauseForClarification(run, planDecision, TeamClarificationStage.PLAN_REVIEW);
            return;
        }
        if (planDecision.action() != ReviewerAction.CONTINUE) {
            failRun(run.getRunId(), "Partial replan was rejected by Reviewer: " + planDecision.reason());
            return;
        }

        executeDagSteps(run, team);
        if (run.getStatus() == TeamRunStatus.FAILED || run.getStatus() == TeamRunStatus.NEEDS_CLARIFICATION) {
            return;
        }
        mergeAndReviewResults(run, team);
    }

    private ReviewerDecision callReviewerForResults(TeamRunEntity run, TeamSnapshot team) {
        TeamMember reviewer = reviewer(team);
        List<TeamStepSnapshot> steps = stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
            .map(this::toStepSnapshot)
            .toList();
        return executeReviewerDecision(reviewer, run, TeamPromptFactory.resultReviewer(run, steps, json), true);
    }

    /**
     * 执行 Reviewer 的审核决策
     * 
     * @param reviewer Reviewer 成员
     * @param run 任务实体
     * @param prompt 审核提示词
     * @param requireFinalReport 是否需要最终报告
     * @return ReviewerDecision 审核决策
     */
    ReviewerDecision executeReviewerDecision(TeamMember reviewer, TeamRunEntity run,
                                             String prompt, boolean requireFinalReport) {
        // 1. 调用 Reviewer Agent 获取原始响应
        String raw = executeTeamControlInternal(reviewer.agentId(), reviewerSession(run), prompt).getFinalResponse();
        
        // 2. Reviewer 必须返回结构化 JSON；格式坏了就用同一 Reviewer 做有限次数修复。
        for (int attempt = 0; attempt <= runtimeProperties.getMaxReviewerFormatRepairs(); attempt++) {
            try {
                // 3. 尝试解析结构化决策
                ReviewerDecision decision = json.parseDecision(raw);
                // 4. 验证决策的有效性
                validateReviewerDecision(decision, requireFinalReport);
                return decision;
            } catch (IllegalArgumentException e) {
                // 5. 解析失败，判断是否还有修复机会
                if (attempt >= runtimeProperties.getMaxReviewerFormatRepairs()) {
                    // 修复次数用完，抛出异常
                    throw e;
                }
                // 6. 还有修复机会，让 Reviewer 修复 JSON 格式
                raw = repairReviewerDecisionJson(reviewer, run, prompt, raw, e.getMessage());
            }
        }
        throw new IllegalStateException("Reviewer decision repair exhausted");
    }

    private String repairReviewerDecisionJson(TeamMember reviewer, TeamRunEntity run,
                                              String originalPrompt, String invalidResponse, String parseError) {
        String repairPrompt = TeamPromptFactory.reviewerRepair(originalPrompt, invalidResponse, parseError);
        return executeTeamControlInternal(reviewer.agentId(), reviewerSession(run) + "-repair-" + UUID.randomUUID(), repairPrompt)
            .getFinalResponse();
    }

    private PipelineContext executeTeamInternal(String agentId, String sessionId, String prompt) {
        return orchestrator.executeInternal(agentId, sessionId, prompt);
    }

    private PipelineContext executeTeamControlInternal(String agentId, String sessionId, String prompt) {
        return orchestrator.executeInternal(agentId, sessionId, prompt, false);
    }

    private void requireRunWritable(String runId) {
        if (isRunDeleted(runId)) {
            throw new IllegalStateException("Run was deleted: " + runId);
        }
    }

    private boolean isRunDeleted(String runId) {
        return deletedRunIds.contains(runId) || !runMapper.existsById(runId);
    }

    private List<TeamStepEntity> retryableSteps(String runId, List<String> retryStepIds) {
        List<TeamStepEntity> all = stepMapper.selectByRunIdOrderByStepIndexAsc(runId);
        if (retryStepIds == null || retryStepIds.isEmpty()) {
            return List.of();
        }
        Set<String> rootIds = resolveStepIds(all, retryStepIds);
        Set<String> expanded = expandWithDownstream(all, rootIds);
        return all.stream()
            .filter(step -> expanded.contains(step.getStepId()))
            .filter(step -> step.getRetryCount() < runtimeProperties.getMaxStepRetries())
            .toList();
    }

    private Set<String> resolveStepIds(List<TeamStepEntity> all, List<String> requestedIds) {
        if (requestedIds == null || requestedIds.isEmpty()) {
            return Set.of();
        }
        Set<String> requested = new HashSet<>(requestedIds);
        Set<String> rootIds = new HashSet<>();
        for (TeamStepEntity step : all) {
            if (requested.contains(step.getStepId()) || requested.contains(step.getClientStepId())) {
                rootIds.add(step.getStepId());
            }
        }
        return rootIds;
    }

    private Set<String> expandWithDownstream(List<TeamStepEntity> all, Set<String> rootIds) {
        Set<String> expanded = new HashSet<>(rootIds);
        boolean changed = true;
        while (changed) {
            changed = false;
            for (TeamStepEntity step : all) {
                if (expanded.contains(step.getStepId())) {
                    continue;
                }
                List<String> dependencies = json.stringList(step.getDependsOnStepIdsJson());
                if (dependencies.stream().anyMatch(expanded::contains)) {
                    expanded.add(step.getStepId());
                    changed = true;
                }
            }
        }
        return expanded;
    }

    private void prepareStepRetry(TeamRunEntity run, TeamStepEntity step, ReviewerDecision decision,
                                  TeamMember reviewer) {
        // 重试前把上一轮输出、审查原因和修正建议沉淀到 Step，下一轮 Executor prompt 会带上。
        List<String> previous = new ArrayList<>(json.stringList(step.getPreviousOutputsJson()));
        if (step.getRawOutput() != null && !step.getRawOutput().isBlank()) {
            previous.add(step.getRawOutput());
        }
        StepReflection reflection = reflectionForStep(step, decision);
        step.setPreviousOutputsJson(json.toJson(previous));
        step.setRevisionInstructions(reflection.revisionInstructions().isBlank()
            ? decision.revisionInstructions()
            : reflection.revisionInstructions());
        step.setLastReviewReason(reflection.reviewReason().isBlank() ? decision.reason() : reflection.reviewReason());
        step.setReflectionJson(json.toJson(reflection));
        step.setRetryCount(step.getRetryCount() + 1);
        step.setStatus(TeamStepStatus.RETRYING);
        step.setQualityStatus(TeamStepQualityStatus.RETRY_REQUESTED);
        step.setReviewStatus("RETRY_REQUESTED");
        step.setStartedAt(null);
        step.setCompletedAt(null);
        stepMapper.upsertById(step);
        recordEvent(run.getRunId(), step.getStepId(), TeamEventType.STEP_REFLECTION_RECORDED, reviewer.role(),
            reviewer.agentId(), "Reviewer 写入 Reflexion 重试上下文", reflection);
        recordEvent(run.getRunId(), step.getStepId(), TeamEventType.RETRY_REQUESTED, reviewer.role(),
            reviewer.agentId(), decision.reason(), decision);
    }

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
            List.of("不要重复上一轮不满足验收标准的输出", "不要只输出工具调用或搜索关键词")
        );
    }

    private String dependencyOutputs(TeamStepEntity step) {
        List<String> dependencyIds = json.stringList(step.getDependsOnStepIdsJson());
        if (dependencyIds.isEmpty()) {
            return "";
        }
        List<Map<String, String>> outputs = new ArrayList<>();
        for (TeamStepEntity dependency : stepMapper.selectByRunIdOrderByStepIndexAsc(step.getRunId())) {
            if (dependencyIds.contains(dependency.getStepId())) {
                outputs.add(Map.of(
                    "stepId", dependency.getStepId(),
                    "title", dependency.getTitle(),
                    "output", nullToBlank(dependency.getRawOutput())
                ));
            }
        }
        return json.toJson(outputs);
    }

    private String summarize(String value) {
        String text = nullToBlank(value).trim();
        return text.length() <= 500 ? text : text.substring(0, 500) + "...";
    }

    /**
     * 暂停任务等待用户澄清
     * 
     * @param run 任务实体
     * @param decision Reviewer 的审核决策
     * @param stage 澄清阶段（计划审核/结果审核）
     */
    private void pauseForClarification(TeamRunEntity run, ReviewerDecision decision, TeamClarificationStage stage) {
        stoppingRunIds.add(run.getRunId());

        String question = decision.questions().isEmpty() ? decision.reason() : String.join("\n", decision.questions());
        run.setClarificationQuestion(question);
        run.setClarificationStage(stage);
        run.setStatus(TeamRunStatus.NEEDS_CLARIFICATION);
        runMapper.upsertById(run);
        recordEvent(run.getRunId(), null, TeamEventType.CLARIFICATION_REQUESTED, TeamRole.REVIEWER,
            null, question, decision);
    }

    private void validateReviewerDecision(ReviewerDecision decision, boolean requireFinalReport) {
        if (decision.action() == ReviewerAction.ASK_CLARIFICATION
            && decision.questions().isEmpty()
            && (decision.reason() == null || decision.reason().isBlank())) {
            throw new IllegalStateException("Reviewer ASK_CLARIFICATION requires a reason or questions");
        }
        if (decision.action() == ReviewerAction.RETRY && requireFinalReport && decision.retryStepIds().isEmpty()) {
            throw new IllegalStateException("Reviewer RETRY requires retryStepIds");
        }
        if (decision.action() == ReviewerAction.PARTIAL_REPLAN
            && requireFinalReport
            && decision.affectedStepIds().isEmpty()
            && decision.retryStepIds().isEmpty()) {
            throw new IllegalStateException("Reviewer PARTIAL_REPLAN requires affectedStepIds or retryStepIds");
        }
        if (decision.action() == ReviewerAction.REPLAN
            && requireFinalReport
            && (decision.revisionInstructions() == null || decision.revisionInstructions().isBlank())
            && (decision.reason() == null || decision.reason().isBlank())) {
            throw new IllegalStateException("Reviewer REPLAN requires a reason or revisionInstructions");
        }
        if (decision.action() == ReviewerAction.FAILED
            && (decision.reason() == null || decision.reason().isBlank())) {
            throw new IllegalStateException("Reviewer FAILED requires a reason");
        }
        if (decision.action() == ReviewerAction.CONTINUE
            && requireFinalReport
            && (decision.finalReport() == null || decision.finalReport().isBlank())) {
            throw new IllegalStateException("Reviewer CONTINUE requires finalReport");
        }
    }

    @Transactional
    protected void markStepFailed(String runId, String stepId, String message) {
        if (isRunDeleted(runId)) {
            return;
        }
        TeamStepEntity step = stepMapper.selectOptionalById(stepId).orElse(null);
        if (step != null) {
            step.setStatus(TeamStepStatus.FAILED);
            step.setRawOutput("[Error] " + nullToBlank(message));
            step.setCompletedAt(Instant.now());
            stepMapper.upsertById(step);
        }
        recordEvent(runId, stepId, TeamEventType.STEP_FAILED, TeamRole.EXECUTOR, null,
            "Step failed: " + nullToBlank(message), null);
    }

    @Transactional
    protected void markStepTimedOut(String runId, String stepId) {
        if (isRunDeleted(runId)) {
            return;
        }
        String message = "Step execution timed out after " + runtimeProperties.getStepTimeoutSeconds() + " seconds";
        TeamStepEntity step = stepMapper.selectOptionalById(stepId).orElse(null);
        if (step != null && step.getStatus() != TeamStepStatus.COMPLETED) {
            step.setStatus(TeamStepStatus.FAILED);
            step.setRawOutput("[Error] " + message);
            step.setCompletedAt(Instant.now());
            stepMapper.upsertById(step);
        }
        recordEvent(runId, stepId, TeamEventType.STEP_TIMEOUT, TeamRole.EXECUTOR, null,
            "Step 超时熔断: " + message, Map.of("stepTimeoutSeconds", runtimeProperties.getStepTimeoutSeconds()));
    }

    // 检查一下是否超时
    private boolean failIfRunTimedOut(TeamRunEntity run) {
        if (runtimeProperties.getRunTimeoutSeconds() <= 0 || run.getCreatedAt() == null) {
            return false;
        }
        Instant deadline = run.getCreatedAt().plusSeconds(runtimeProperties.getRunTimeoutSeconds());
        if (Instant.now().isBefore(deadline)) {
            return false;
        }
        recordEvent(run.getRunId(), null, TeamEventType.RUN_TIMEOUT, null, null,
            "Run 超时熔断", Map.of("runTimeoutSeconds", runtimeProperties.getRunTimeoutSeconds()));
        failRun(run.getRunId(), "Run execution timed out after " + runtimeProperties.getRunTimeoutSeconds() + " seconds");
        return true;
    }

    @Transactional
    protected void failRun(String runId, String message) {
        stoppingRunIds.add(runId);
        pendingClarifications.remove(runId);
        TeamRunEntity run = runMapper.selectOptionalById(runId).orElse(null);
        if (run != null) {
            run.setStatus(TeamRunStatus.FAILED);
            run.setFinalOutput("[Error] " + nullToBlank(message));
            runMapper.upsertById(run);
        }
        recordEvent(runId, null, TeamEventType.RUN_FAILED, null, null,
            "Run failed: " + nullToBlank(message), null);
    }

    private void replaceSteps(TeamRunEntity run, List<PlannedStep> planned) {
        replaceSteps(run, planned, false);
    }

    /**
     * 将 Planner 生成的计划步骤转换为数据库中的 Step 记录
     * 
     * @param run 任务实体
     * @param planned Planner 生成的计划步骤列表
     * @param preserveExisting 是否保留现有步骤（标记为 SUPERSEDED）
     */
    private void replaceSteps(TeamRunEntity run, List<PlannedStep> planned, boolean preserveExisting) {
        // 将 Planner 的 clientStepId DAG 落成数据库 Step；dependsOn 统一转换为真实 stepId。
        int baseIndex = 0;
        
        // 1. 处理现有步骤
        if (preserveExisting) {
            // 保留模式：将现有步骤标记为 SUPERSEDED
            for (TeamStepEntity existing : stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId())) {
                if (existing.getStatus() != TeamStepStatus.SUPERSEDED) {
                    existing.setStatus(TeamStepStatus.SUPERSEDED);
                    stepMapper.upsertById(existing);
                }
                // 记录最大的步骤索引，新步骤从这个索引之后开始
                baseIndex = Math.max(baseIndex, existing.getStepIndex());
            }
        } else {
            // 不保留模式：直接删除所有现有步骤
            stepMapper.deleteByRunId(run.getRunId());
        }
        
        // 2. 过滤出可执行的步骤（排除最终集成步骤）
        List<PlannedStep> executable = planned.stream()
            .filter(item -> !isFinalIntegrationStep(item))
            .toList();
        
        // 3. 验证计划的 DAG 结构
        validatePlannedDag(executable);
        
        // 4. 判断是否为简单草稿模式（简单任务且只有一个步骤）
        boolean simpleDraft = run.getTaskLevel() == TeamTaskLevel.SIMPLE && executable.size() == 1;

        // 5. 构建 clientStepId 到真实 stepId 的映射
        Map<String, String> clientToStepId = new LinkedHashMap<>();
        int index = 1;
        for (PlannedStep item : executable) {
            String clientStepId = simpleDraft ? "simple-draft" : clientStepId(item, index);
            // 生成唯一的 stepId：step-{index}-{uuid前8位}
            clientToStepId.put(clientStepId, "step-" + index + "-" + UUID.randomUUID().toString().substring(0, 8));
            index++;
        }

        // 6. 创建数据库 Step 记录
        index = 1;
        for (PlannedStep item : executable) {
            String clientStepId = simpleDraft ? "simple-draft" : clientStepId(item, index);
            
            // 将依赖的 clientStepId 转换为真实的 stepId
            List<String> dependencyStepIds = item.dependsOn().stream()
                .map(clientToStepId::get)
                .filter(id -> id != null && !id.isBlank())
                .toList();
            
            // 创建 Step 实体
            TeamStepEntity step = new TeamStepEntity();
            step.setStepId(clientToStepId.get(clientStepId));
            step.setRunId(run.getRunId());
            step.setStepIndex(baseIndex + index);  // 从 baseIndex 之后开始编号
            step.setClientStepId(clientStepId);
            step.setTitle(item.title());
            step.setDescription(item.description());
            step.setRequiredCapabilitiesJson(json.toJson(item.requiredCapabilities()));
            step.setDependsOnStepIdsJson(json.toJson(dependencyStepIds));
            
            // 风险评估
            TeamRiskDecision riskDecision = riskPolicy.decide(item);
            step.setRiskLevel(riskDecision.level());
            step.setRiskReason(riskDecision.reason());
            
            step.setAcceptanceCriteria(item.acceptanceCriteria());
            step.setStatus(TeamStepStatus.PENDING);           // 初始状态为待执行
            step.setQualityStatus(TeamStepQualityStatus.PENDING);  // 质量状态待审核
            step.setPlanIteration(run.getFullReplanCount() + run.getPartialReplanCount() + run.getResultReplanCount());
            
            // 保存到数据库
            stepMapper.upsertById(step);
            
            // 记录风险判定事件
            recordEvent(run.getRunId(), step.getStepId(), TeamEventType.RISK_DECIDED, null, null,
                "RiskPolicy 判定为" + (riskDecision.level() == TeamRiskLevel.HIGH ? "高风险" : "低风险")
                    + "：" + riskDecision.reason(), riskDecision);
            
            index++;
        }
    }

    private String clientStepId(PlannedStep item, int index) {
        return item.clientStepId() == null || item.clientStepId().isBlank()
            ? "step-" + index
            : item.clientStepId().trim();
    }

    private void appendPartialReplannedSteps(TeamRunEntity run, List<PlannedStep> planned) {
        // 局部重规划新增替代 Step，依赖可以指向新子图，也可以指向未被替换的旧 Step。
        List<TeamStepEntity> existingSteps = stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId());
        List<PlannedStep> executable = planned.stream()
            .filter(item -> !isFinalIntegrationStep(item))
            .toList();
        validatePlannedDag(executable);
        int baseIndex = existingSteps.stream()
            .mapToInt(TeamStepEntity::getStepIndex)
            .max()
            .orElse(0);
        Map<String, String> replacementClientToStepId = new LinkedHashMap<>();
        for (int i = 0; i < executable.size(); i++) {
            PlannedStep item = executable.get(i);
            String clientStepId = item.clientStepId() == null || item.clientStepId().isBlank()
                ? "partial-step-" + (i + 1)
                : item.clientStepId().trim();
            replacementClientToStepId.put(clientStepId,
                "step-" + (baseIndex + i + 1) + "-" + UUID.randomUUID().toString().substring(0, 8));
        }

        for (int i = 0; i < executable.size(); i++) {
            PlannedStep item = executable.get(i);
            String clientStepId = item.clientStepId() == null || item.clientStepId().isBlank()
                ? "partial-step-" + (i + 1)
                : item.clientStepId().trim();
            List<String> dependencyStepIds = item.dependsOn().stream()
                .map(dependency -> resolveDependencyForReplacement(dependency, replacementClientToStepId, existingSteps))
                .filter(id -> id != null && !id.isBlank())
                .toList();
            TeamRiskDecision riskDecision = riskPolicy.decide(item);
            TeamStepEntity step = new TeamStepEntity();
            step.setStepId(replacementClientToStepId.get(clientStepId));
            step.setRunId(run.getRunId());
            step.setStepIndex(baseIndex + i + 1);
            step.setClientStepId(clientStepId);
            step.setTitle(item.title());
            step.setDescription(item.description());
            step.setRequiredCapabilitiesJson(json.toJson(item.requiredCapabilities()));
            step.setDependsOnStepIdsJson(json.toJson(dependencyStepIds));
            step.setRiskLevel(riskDecision.level());
            step.setRiskReason(riskDecision.reason());
            step.setAcceptanceCriteria(item.acceptanceCriteria());
            step.setStatus(TeamStepStatus.PENDING);
            step.setQualityStatus(TeamStepQualityStatus.PENDING);
            step.setPlanIteration(run.getFullReplanCount() + run.getPartialReplanCount() + run.getResultReplanCount());
            stepMapper.upsertById(step);
            recordEvent(run.getRunId(), step.getStepId(), TeamEventType.RISK_DECIDED, null, null,
                "RiskPolicy 判定为" + (riskDecision.level() == TeamRiskLevel.HIGH ? "高风险" : "低风险")
                    + "：" + riskDecision.reason(), riskDecision);
        }
    }

    private String resolveDependencyForReplacement(String dependency,
                                                   Map<String, String> replacementClientToStepId,
                                                   List<TeamStepEntity> existingSteps) {
        if (dependency == null || dependency.isBlank()) {
            return null;
        }
        String trimmed = dependency.trim();
        if (replacementClientToStepId.containsKey(trimmed)) {
            return replacementClientToStepId.get(trimmed);
        }
        return existingSteps.stream()
            .filter(step -> step.getStatus() != TeamStepStatus.SUPERSEDED)
            .filter(step -> trimmed.equals(step.getStepId()) || trimmed.equals(step.getClientStepId()))
            .map(TeamStepEntity::getStepId)
            .findFirst()
            .orElse(null);
    }

    private void validatePlannedDag(List<PlannedStep> planned) {
        // Planner 输出进入数据库前必须先过结构校验，避免运行期才发现断边或环。
        if (planned == null || planned.isEmpty()) {
            throw new IllegalStateException("Planner did not produce executable steps");
        }
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < planned.size(); i++) {
            PlannedStep step = planned.get(i);
            String clientStepId = step.clientStepId() == null || step.clientStepId().isBlank()
                ? "step-" + (i + 1)
                : step.clientStepId().trim();
            if (!ids.add(clientStepId)) {
                throw new IllegalStateException("Planner produced duplicate clientStepId: " + clientStepId);
            }
        }
        for (int i = 0; i < planned.size(); i++) {
            PlannedStep step = planned.get(i);
            String clientStepId = step.clientStepId() == null || step.clientStepId().isBlank()
                ? "step-" + (i + 1)
                : step.clientStepId().trim();
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
            String clientStepId = step.clientStepId() == null || step.clientStepId().isBlank()
                ? "step-" + (i + 1)
                : step.clientStepId().trim();
            graph.put(clientStepId, step.dependsOn());
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

    private boolean hasCycle(String id, Map<String, List<String>> graph, Set<String> visiting, Set<String> visited) {
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
        // 最终报告由 Reviewer 生成；Planner 如果误产出“汇总/整合”Step，这里过滤掉。
        String text = (nullToBlank(item.title()) + " " + nullToBlank(item.description()) + " "
            + nullToBlank(item.acceptanceCriteria())).toLowerCase();
        return containsAny(text,
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
            "integrated report"
        );
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
    protected void recordEvent(String runId, String stepId, TeamEventType type, TeamRole actorRole,
                               String actorAgentId, String message, Object payload) {
        // Event 是 Team 看板和 Mermaid 的审计来源，只追加，不参与状态判断。
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
            normalized.add(new TeamMemberSpec(
                spec.agentId(),
                spec.role(),
                spec.capabilityTags() == null ? List.of() : spec.capabilityTags(),
                spec.sortOrder() == 0 ? fallbackOrder : spec.sortOrder()
            ));
            fallbackOrder += 10;
        }
        return normalized;
    }

    private void validateMembers(List<TeamMemberSpec> specs) {
        long planners = specs.stream().filter(spec -> spec.role() == TeamRole.PLANNER).count();
        long executors = specs.stream().filter(spec -> spec.role() == TeamRole.EXECUTOR).count();
        long reviewers = specs.stream().filter(spec -> spec.role() == TeamRole.REVIEWER).count();
        if (planners != 1 || executors < 1 || reviewers != 1) {
            throw new IllegalArgumentException("Team requires exactly one Planner, at least one Executor, and exactly one Reviewer");
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
        // 加载团队协作任务实体
        TeamRunEntity run = runMapper.selectOptionalById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        if (!run.getTeamId().equals(teamId)) {
            throw new IllegalArgumentException("Run does not belong to team: " + runId);
        }
        if (!normalizeUserId(run.getUserId()).equals(normalizeUserId(userId))) {
            throw new IllegalArgumentException("Run does not belong to current user: " + runId);
        }
        return run;
    }

    private TeamSnapshot toTeamSnapshot(TeamEntity team) {
        List<TeamMember> members = memberMapper.selectByTeamIdOrderBySortOrderAscIdAsc(team.getTeamId()).stream()
            .map(this::toMember)
            .toList();
        return new TeamSnapshot(team.getTeamId(), team.getOwnerUserId(), team.getName(), members, team.getCreatedAt(), team.getUpdatedAt());
    }

    private TeamMember toMember(TeamMemberEntity entity) {
        Agent agent = agentFactory.get(entity.getAgentId());
        return new TeamMember(
            entity.getAgentId(),
            agent == null ? entity.getAgentId() : agent.getName(),
            entity.getRole(),
            json.stringList(entity.getCapabilityTagsJson()),
            entity.getSortOrder(),
            agent
        );
    }

    private TeamRunSnapshot toRunSnapshot(TeamRunEntity run, boolean includeSteps, boolean includeEvents) {
        // 前端 Team 看板只读 Snapshot；这里集中完成 Entity -> DTO 和动态图生成。
        List<TeamStepSnapshot> steps = includeSteps
            ? stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId()).stream().map(this::toStepSnapshot).toList()
            : List.of();
        List<TeamEventSnapshot> events = includeEvents
            ? eventMapper.selectByRunIdOrderByCreatedAtAscIdAsc(run.getRunId()).stream().map(this::toEventSnapshot).toList()
            : List.of();
        return new TeamRunSnapshot(
            run.getRunId(),
            run.getTeamId(),
            run.getUserId(),
            run.getTask(),
            run.getStatus(),
            run.getTaskLevel() == null ? TeamTaskLevel.COMPLEX.name() : run.getTaskLevel().name(),
            run.getClarificationQuestion(),
            run.getClarificationAnswer(),
            run.getClarificationStage() == null ? null : run.getClarificationStage().name(),
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
            run.getUpdatedAt()
        );
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
            step.getCompletedAt()
        );
    }

    private String dynamicMermaid(TeamRunEntity run, List<TeamStepSnapshot> steps, List<TeamEventSnapshot> events) {
        if (steps == null || steps.isEmpty()) {
            return run.getMermaidDiagram();
        }
        return MermaidGenerator.generateFromSnapshots(
            getTeam(run.getTeamId()).name(),
            run.getStatus(),
            run.getTaskLevel() == null ? TeamTaskLevel.COMPLEX.name() : run.getTaskLevel().name(),
            steps,
            events == null ? List.of() : events
        );
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
            event.getCreatedAt()
        );
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
            TeamMember byKey = team.members().stream()
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

    private String synthesizeFallbackReport(TeamRunEntity run) {
        StringBuilder output = new StringBuilder();
        output.append("任务完成。\n\n");
        for (TeamStepEntity step : stepMapper.selectByRunIdOrderByStepIndexAsc(run.getRunId())) {
            output.append("## ").append(step.getTitle()).append("\n")
                .append(nullToBlank(step.getRawOutput())).append("\n\n");
        }
        return output.toString().trim();
    }

    private boolean isTerminal(TeamRunStatus status) {
        return status == TeamRunStatus.COMPLETED
            || status == TeamRunStatus.FAILED
            || status == TeamRunStatus.NEEDS_CLARIFICATION;
    }

    private void scheduleRun(String runId) {
        // create/resume 都可能在事务中调用；注册 afterCommit 能避免后台线程提前执行。
        Runnable task = () -> executeRunSafely(runId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    taskExecutor.execute(task);
                }
            });
        } else {
            taskExecutor.execute(task);
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

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "default" : userId;
    }
}
