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
import com.echomind.agent.team.store.TeamEventRepository;
import com.echomind.agent.team.store.TeamMemberEntity;
import com.echomind.agent.team.store.TeamMemberRepository;
import com.echomind.agent.team.store.TeamRepository;
import com.echomind.agent.team.store.TeamRunEntity;
import com.echomind.agent.team.store.TeamRunRepository;
import com.echomind.agent.team.store.TeamStepEntity;
import com.echomind.agent.team.store.TeamStepRepository;
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
 */
@Slf4j
@Service
public class TeamBlackboardService {

    private final TeamRepository teamRepository;
    private final TeamMemberRepository memberRepository;
    private final TeamRunRepository runRepository;
    private final TeamStepRepository stepRepository;
    private final TeamEventRepository eventRepository;
    private final AgentFactory agentFactory;
    private final AgentOrchestrator orchestrator;
    private final TaskExecutor taskExecutor;
    private final TeamRuntimeProperties runtimeProperties;

    private final TeamJsonSupport json = new TeamJsonSupport();
    private final TeamAgentSelector agentSelector = new TeamAgentSelector();
    private final TeamRiskPolicy riskPolicy = new TeamRiskPolicy();
    private final Set<String> deletedRunIds = ConcurrentHashMap.newKeySet();

    public TeamBlackboardService(TeamRepository teamRepository,
                                 TeamMemberRepository memberRepository,
                                 TeamRunRepository runRepository,
                                 TeamStepRepository stepRepository,
                                 TeamEventRepository eventRepository,
                                 AgentFactory agentFactory,
                                 AgentOrchestrator orchestrator,
                                 @Qualifier("teamTaskExecutor") TaskExecutor taskExecutor) {
        this(teamRepository, memberRepository, runRepository, stepRepository, eventRepository,
            agentFactory, orchestrator, taskExecutor, new TeamRuntimeProperties());
    }

    @Autowired
    public TeamBlackboardService(TeamRepository teamRepository,
                                 TeamMemberRepository memberRepository,
                                 TeamRunRepository runRepository,
                                 TeamStepRepository stepRepository,
                                 TeamEventRepository eventRepository,
                                 AgentFactory agentFactory,
                                 AgentOrchestrator orchestrator,
                                 @Qualifier("teamTaskExecutor") TaskExecutor taskExecutor,
                                 TeamRuntimeProperties runtimeProperties) {
        this.teamRepository = teamRepository;
        this.memberRepository = memberRepository;
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.eventRepository = eventRepository;
        this.agentFactory = agentFactory;
        this.orchestrator = orchestrator;
        this.taskExecutor = taskExecutor;
        this.runtimeProperties = runtimeProperties == null ? new TeamRuntimeProperties() : runtimeProperties;
    }

    @Transactional(readOnly = true)
    public List<TeamSnapshot> listTeams() {
        return teamRepository.findAll().stream()
            .sorted(Comparator.comparing(TeamEntity::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
            .map(this::toTeamSnapshot)
            .toList();
    }

    @Transactional(readOnly = true)
    public TeamSnapshot getTeam(String teamId) {
        return teamRepository.findById(teamId)
            .map(this::toTeamSnapshot)
            .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
    }

    @Transactional
    public TeamSnapshot createTeam(String requestedName, List<TeamMemberSpec> specs) {
        List<TeamMemberSpec> normalized = normalizeSpecs(specs);
        validateMembers(normalized);

        TeamEntity team = new TeamEntity();
        team.setTeamId(UUID.randomUUID().toString());
        team.setName(requestedName == null || requestedName.isBlank()
            ? "Team-" + UUID.randomUUID().toString().substring(0, 8)
            : requestedName.trim());
        teamRepository.save(team);

        for (TeamMemberSpec spec : normalized) {
            TeamMemberEntity member = new TeamMemberEntity();
            member.setTeamId(team.getTeamId());
            member.setAgentId(spec.agentId());
            member.setRole(spec.role());
            member.setCapabilityTagsJson(json.toJson(spec.capabilityTags() == null ? List.of() : spec.capabilityTags()));
            member.setSortOrder(spec.sortOrder());
            memberRepository.save(member);
        }

        return getTeam(team.getTeamId());
    }

    @Transactional
    public void deleteTeam(String teamId) {
        if (teamId == null || teamId.isBlank()) {
            throw new IllegalArgumentException("teamId is required");
        }
        TeamEntity team = teamRepository.findById(teamId)
            .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
        List<String> runIds = runRepository.findByTeamIdOrderByCreatedAtDesc(team.getTeamId()).stream()
            .map(TeamRunEntity::getRunId)
            .toList();
        deletedRunIds.addAll(runIds);
        if (!runIds.isEmpty()) {
            eventRepository.deleteByRunIdIn(runIds);
            stepRepository.deleteByRunIdIn(runIds);
        }
        runRepository.deleteByTeamId(team.getTeamId());
        memberRepository.deleteByTeamId(team.getTeamId());
        teamRepository.delete(team);
    }

    @Transactional
    public TeamRunSnapshot createRun(String teamId, String task) {
        return createRun(teamId, "default", task);
    }

    @Transactional
    public TeamRunSnapshot createRun(String teamId, String userId, String task) {
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task is required");
        }
        getTeam(teamId);
        TeamRunEntity run = new TeamRunEntity();
        run.setRunId(UUID.randomUUID().toString());
        run.setTeamId(teamId);
        run.setUserId(normalizeUserId(userId));
        run.setTask(task.trim());
        run.setStatus(TeamRunStatus.PENDING);
        runRepository.save(run);
        recordEvent(run.getRunId(), null, TeamEventType.RUN_CREATED, null, null, "Run created", null);
        scheduleRun(run.getRunId());
        return getRun(teamId, userId, run.getRunId());
    }

    @Transactional(readOnly = true)
    public List<TeamRunSnapshot> listRuns(String teamId) {
        return listRuns(teamId, "default");
    }

    @Transactional(readOnly = true)
    public List<TeamRunSnapshot> listRuns(String teamId, String userId) {
        getTeam(teamId);
        return runRepository.findByTeamIdAndUserIdOrderByCreatedAtDesc(teamId, normalizeUserId(userId)).stream()
            .map(run -> toRunSnapshot(run, true, false))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<TeamRunSnapshot> listRunsForUser(String userId) {
        return runRepository.findByUserIdOrderByCreatedAtDesc(normalizeUserId(userId)).stream()
            .map(run -> toRunSnapshot(run, true, false))
            .toList();
    }

    @Transactional(readOnly = true)
    public TeamRunSnapshot getRun(String teamId, String runId) {
        return getRun(teamId, "default", runId);
    }

    @Transactional(readOnly = true)
    public TeamRunSnapshot getRun(String teamId, String userId, String runId) {
        TeamRunEntity run = loadRun(teamId, normalizeUserId(userId), runId);
        return toRunSnapshot(run, true, true);
    }

    @Transactional
    public TeamRunSnapshot resumeRun(String teamId, String runId, String clarificationAnswer) {
        return resumeRun(teamId, "default", runId, clarificationAnswer);
    }

    @Transactional
    public TeamRunSnapshot resumeRun(String teamId, String userId, String runId, String clarificationAnswer) {
        TeamRunEntity run = loadRun(teamId, normalizeUserId(userId), runId);
        if (run.getStatus() != TeamRunStatus.NEEDS_CLARIFICATION) {
            throw new IllegalArgumentException("Run is not waiting for clarification: " + runId);
        }
        if (clarificationAnswer == null || clarificationAnswer.isBlank()) {
            throw new IllegalArgumentException("clarificationAnswer is required");
        }
        run.setClarificationAnswer(clarificationAnswer.trim());
        if (run.getClarificationStage() == TeamClarificationStage.PLAN_REVIEW) {
            stepRepository.deleteByRunId(runId);
            run.setPlanReviewJson(null);
            run.setResultReviewJson(null);
            run.setFinalOutput(null);
            run.setMermaidDiagram(null);
        } else if (run.getClarificationStage() == TeamClarificationStage.RESULT_REVIEW) {
            prepareStepsForClarificationResume(run);
            run.setResultReviewJson(null);
            run.setFinalOutput(null);
            run.setMermaidDiagram(null);
        }
        run.setStatus(TeamRunStatus.PENDING);
        runRepository.save(run);
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

    @Transactional
    protected void executeRun(String runId) {
        TeamRunEntity run = runRepository.findById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        TeamSnapshot team = getTeam(run.getTeamId());

        if (failIfRunTimedOut(run)) {
            return;
        }
        List<TeamStepEntity> existingSteps = stepRepository.findByRunIdOrderByStepIndexAsc(runId);
        if (existingSteps.isEmpty()) {
            planAndReview(run, team);
            if (run.getStatus() == TeamRunStatus.NEEDS_CLARIFICATION || run.getStatus() == TeamRunStatus.FAILED) {
                return;
            }
        }

        if (run.getTaskLevel() == TeamTaskLevel.SIMPLE) {
            executeSimpleRun(run, team);
            return;
        }

        executeDagSteps(run, team);
        run = runRepository.findById(runId).orElse(run);
        if (run.getStatus() == TeamRunStatus.FAILED || run.getStatus() == TeamRunStatus.NEEDS_CLARIFICATION) {
            return;
        }

        mergeAndReviewResults(run, team);
    }

    private void executeSimpleRun(TeamRunEntity run, TeamSnapshot team) {
        recordEvent(run.getRunId(), null, TeamEventType.SIMPLE_DRAFT_STARTED, TeamRole.EXECUTOR,
            null, "简易任务进入快路径：直接生成初稿并交给 GlobalReviewer 简易终审", null);
        executeDagSteps(run, team);
        TeamRunEntity latest = runRepository.findById(run.getRunId()).orElse(run);
        if (latest.getStatus() == TeamRunStatus.FAILED || latest.getStatus() == TeamRunStatus.NEEDS_CLARIFICATION) {
            return;
        }
        recordEvent(run.getRunId(), null, TeamEventType.SIMPLE_DRAFT_COMPLETED, TeamRole.EXECUTOR,
            null, "简易任务初稿已完成", null);
        mergeAndReviewResults(latest, team);
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
        boolean approved = false;
        List<PlannedStep> previousPlan = initialPreviousPlan == null ? List.of() : initialPreviousPlan;
        String plannerRevisionInstructions = nullToBlank(initialRevisionInstructions);
        while (!approved && run.getPlanRetryCount() <= runtimeProperties.getMaxPlanRetries()) {
            run.setStatus(TeamRunStatus.PLANNING);
            runRepository.save(run);
            recordEvent(run.getRunId(), null, TeamEventType.PLAN_STARTED, TeamRole.PLANNER,
                planner(team).agentId(), "Planner started task decomposition", null);

            List<PlannedStep> planned = callPlanner(run, team, previousPlan, plannerRevisionInstructions,
                previousExecutionResults);
            requireRunWritable(run.getRunId());
            replaceSteps(run, planned, preserveExistingSteps);
            recordEvent(run.getRunId(), null, TeamEventType.PLAN_CREATED, TeamRole.PLANNER,
                planner(team).agentId(), "Planner created " + planned.size() + " step(s)", planned);

            run.setStatus(TeamRunStatus.PLAN_REVIEWING);
            runRepository.save(run);
            recordEvent(run.getRunId(), null, TeamEventType.PLAN_REVIEW_STARTED, TeamRole.REVIEWER,
                reviewer(team).agentId(), "Reviewer started plan review", null);

            ReviewerDecision decision = callReviewerForPlan(run, team, planned);
            requireRunWritable(run.getRunId());
            run.setPlanReviewJson(json.toJson(decision));
            runRepository.save(run);
            recordEvent(run.getRunId(), null, TeamEventType.PLAN_REVIEWED, TeamRole.REVIEWER,
                reviewer(team).agentId(), decision.reason(), decision);

            if (decision.action() == ReviewerAction.CONTINUE) {
                approved = true;
            } else if (decision.action() == ReviewerAction.ASK_CLARIFICATION) {
                pauseForClarification(run, decision, TeamClarificationStage.PLAN_REVIEW);
                return;
            } else if (run.getPlanRetryCount() >= runtimeProperties.getMaxPlanRetries()) {
                failRun(run.getRunId(), "Planner retry limit reached: " + decision.reason());
                return;
            } else {
                previousPlan = planned;
                plannerRevisionInstructions = decision.revisionInstructions();
                run.setPlanRetryCount(run.getPlanRetryCount() + 1);
                runRepository.save(run);
                recordEvent(run.getRunId(), null, TeamEventType.RETRY_REQUESTED, TeamRole.REVIEWER,
                    reviewer(team).agentId(), "Reviewer requested planner retry: " + decision.reason(), decision);
            }
        }
    }

    private List<PlannedStep> callPlanner(TeamRunEntity run, TeamSnapshot team,
                                          List<PlannedStep> previousPlan,
                                          String plannerRevisionInstructions,
                                          List<TeamStepSnapshot> previousExecutionResults) {
        TeamMember planner = planner(team);
        String prompt = buildPlannerPrompt(run, team, previousPlan, plannerRevisionInstructions,
            previousExecutionResults);
        PipelineContext result = executeTeamInternal(planner.agentId(), plannerSession(run), prompt);
        String raw = result.getFinalResponse();
        List<PlannedStep> steps = json.parsePlan(raw);
        if (steps.isEmpty()) {
            throw new IllegalStateException("Planner did not produce any steps");
        }
        run.setTaskLevel(json.parseTaskLevel(raw, steps.size()));
        runRepository.save(run);
        return steps;
    }

    String buildPlannerPrompt(TeamRunEntity run, TeamSnapshot team, List<PlannedStep> previousPlan,
                              String plannerRevisionInstructions) {
        return buildPlannerPrompt(run, team, previousPlan, plannerRevisionInstructions, List.of());
    }

    String buildPlannerPrompt(TeamRunEntity run, TeamSnapshot team, List<PlannedStep> previousPlan,
                              String plannerRevisionInstructions,
                              List<TeamStepSnapshot> previousExecutionResults) {
        return """
            You are the Planner in an Agent Team. For SIMPLE tasks create exactly 1 executable subtask; for COMPLEX tasks decompose into 2-5 executable subtasks.
            Write all user-visible text in Simplified Chinese.
            Plan only information-gathering or analysis subtasks for Executors.
            Do not create a final integration, final report, final方案整合, 汇总输出, or 可执行方案撰写 step. The Reviewer is responsible for the final report.
            Decide taskLevel as SIMPLE only when one direct worker result is enough; otherwise use COMPLEX.
            Build a DAG: clientStepId is stable within this plan, dependsOn references previous clientStepId values, and dependency cycles are forbidden.
            Set riskLevel to HIGH only when the Step needs reviewer validation before downstream work can trust it; otherwise LOW.
            Respond only with JSON:
            {
              "taskLevel": "SIMPLE | COMPLEX",
              "steps": [
                {
                  "clientStepId": "stable-id",
                  "title": "short title",
                  "description": "specific executor task",
                  "dependsOn": ["clientStepId"],
                  "riskLevel": "LOW | HIGH",
                  "riskReason": "why high risk, empty if low",
                  "requiredCapabilities": ["search", "weather", "coordination"],
                  "acceptanceCriteria": "what a good result must contain"
                }
              ]
            }

            User task:
            %s

            User clarification, if any:
            %s

            Previous plan, if Reviewer requested replanning:
            %s

            Reviewer revision instructions, if any:
            %s

            Previous executor results, if replanning after result review:
            %s

            Available executor capabilities:
            %s
            """.formatted(
            run.getTask(),
            nullToBlank(run.getClarificationAnswer()),
            json.toJson(previousPlan == null ? List.of() : previousPlan),
            nullToBlank(plannerRevisionInstructions),
            json.toJson(previousExecutionResults == null ? List.of() : previousExecutionResults),
            executorCapabilities(team)
        );
    }

    private ReviewerDecision callReviewerForPlan(TeamRunEntity run, TeamSnapshot team, List<PlannedStep> planned) {
        TeamMember reviewer = reviewer(team);
        String prompt = """
            You are the Reviewer and quality gate for an Agent Team.
            Review whether the Planner's subtasks cover the original user need, are actionable, and have clear capabilities.
            Write reason, questions, revisionInstructions, and finalReport in Simplified Chinese.
            Respond as one single-line valid JSON object only.
            Text fields must not contain raw line breaks. Encode line breaks as \\n only when needed.
            Do not use ASCII double quotes inside text values; use Chinese corner quotes 「」 instead.
            Respond only with JSON:
            {
              "action": "CONTINUE | RETRY | ASK_CLARIFICATION",
              "reason": "why",
              "questions": ["only if clarification is needed"],
              "retryStepIds": [],
              "revisionInstructions": "instructions for Planner if retry is needed",
              "finalReport": ""
            }

            Original task:
            %s

            User clarification, if any:
            %s

            Planned steps:
            %s
            """.formatted(run.getTask(), nullToBlank(run.getClarificationAnswer()), json.toJson(planned));

        return executeReviewerDecision(reviewer, run, prompt, false);
    }

    private void executeDagSteps(TeamRunEntity run, TeamSnapshot team) {
        run.setStatus(TeamRunStatus.EXECUTING);
        runRepository.save(run);
        recordEvent(run.getRunId(), null, TeamEventType.TEAM_CONTROL_STARTED, null, null,
            "TeamControlCenter 启动 DAG 调度、并发限制、超时熔断和迭代拦截", Map.of(
                "maxConcurrentSteps", runtimeProperties.getMaxConcurrentSteps(),
                "maxStepRetries", runtimeProperties.getMaxStepRetries(),
                "stepTimeoutSeconds", runtimeProperties.getStepTimeoutSeconds(),
                "runTimeoutSeconds", runtimeProperties.getRunTimeoutSeconds()
            ));

        while (true) {
            if (failIfRunTimedOut(run)) {
                return;
            }
            markReadySteps(run);
            List<TeamStepEntity> readySteps = stepRepository.findByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
                .filter(step -> step.getStatus() == TeamStepStatus.READY)
                .limit(runtimeProperties.getMaxConcurrentSteps())
                .toList();
            if (readySteps.isEmpty()) {
                List<TeamStepEntity> all = stepRepository.findByRunIdOrderByStepIndexAsc(run.getRunId());
                if (all.stream().anyMatch(step -> step.getStatus() == TeamStepStatus.FAILED)) {
                    failRun(run.getRunId(), "存在失败 Step，DAG 停止执行");
                    return;
                }
                boolean allDone = all.stream().allMatch(step ->
                    step.getStatus() == TeamStepStatus.COMPLETED || step.getStatus() == TeamStepStatus.SUPERSEDED);
                if (allDone) {
                    return;
                }
                failRun(run.getRunId(), "DAG 依赖无法继续推进，请检查 Planner 输出的 dependsOn");
                return;
            }

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (TeamStepEntity step : readySteps) {
                CompletableFuture<Void> future = CompletableFuture
                    .runAsync(() -> executeStepSafely(run.getRunId(), step.getStepId()), taskExecutor);
                if (runtimeProperties.getStepTimeoutSeconds() > 0) {
                    future = future.orTimeout(runtimeProperties.getStepTimeoutSeconds(), TimeUnit.SECONDS);
                }
                futures.add(future.exceptionally(error -> {
                    handleStepFutureFailure(run.getRunId(), step.getStepId(), error);
                    return null;
                }));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            TeamRunEntity latest = runRepository.findById(run.getRunId()).orElse(run);
            run.setStatus(latest.getStatus());
            if (latest.getStatus() == TeamRunStatus.NEEDS_CLARIFICATION || latest.getStatus() == TeamRunStatus.FAILED) {
                return;
            }
        }
    }

    private void executePendingSteps(TeamRunEntity run, TeamSnapshot team) {
        executeDagSteps(run, team);
    }

    private void markReadySteps(TeamRunEntity run) {
        List<TeamStepEntity> all = stepRepository.findByRunIdOrderByStepIndexAsc(run.getRunId());
        Set<String> completed = new HashSet<>();
        for (TeamStepEntity step : all) {
            if (step.getStatus() == TeamStepStatus.COMPLETED || step.getStatus() == TeamStepStatus.SUPERSEDED) {
                completed.add(step.getStepId());
            }
        }
        for (TeamStepEntity step : all) {
            if (step.getStatus() != TeamStepStatus.PENDING
                && step.getStatus() != TeamStepStatus.BLOCKED
                && step.getStatus() != TeamStepStatus.RETRYING) {
                continue;
            }
            List<String> dependencies = json.stringList(step.getDependsOnStepIdsJson());
            if (completed.containsAll(dependencies)) {
                step.setStatus(TeamStepStatus.READY);
                stepRepository.save(step);
                recordEvent(run.getRunId(), step.getStepId(), TeamEventType.STEP_READY, null, null,
                    "Step 依赖已完成，进入可执行队列", Map.of("dependsOnStepIds", dependencies));
            } else if (step.getStatus() == TeamStepStatus.PENDING) {
                step.setStatus(TeamStepStatus.BLOCKED);
                stepRepository.save(step);
                recordEvent(run.getRunId(), step.getStepId(), TeamEventType.STEP_BLOCKED, null, null,
                    "Step 等待前置依赖完成", Map.of("dependsOnStepIds", dependencies));
            }
        }
    }

    private void executeStepSafely(String runId, String stepId) {
        try {
            executeStep(runId, stepId);
        } catch (Exception e) {
            log.error("Team step failed: {}", stepId, e);
            markStepFailed(runId, stepId, e.getMessage());
        }
    }

    private void handleStepFutureFailure(String runId, String stepId, Throwable error) {
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

    @Transactional
    protected void executeStep(String runId, String stepId) {
        TeamRunEntity run = runRepository.findById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        TeamSnapshot team = getTeam(run.getTeamId());
        TeamStepEntity step = stepRepository.findById(stepId)
            .orElseThrow(() -> new IllegalArgumentException("Step not found: " + stepId));

        List<String> requiredCapabilities = json.stringList(step.getRequiredCapabilitiesJson());
        List<TeamStepEntity> runSteps = stepRepository.findByRunIdOrderByStepIndexAsc(runId);
        TeamAgentSelection selection = selectExecutorWithModel(run, team, step, requiredCapabilities, runSteps);
        TeamMember executor = selection == null ? null : executorBySelection(team, selection);
        if (executor == null) {
            throw new IllegalStateException("No executor configured");
        }
        recordEvent(runId, stepId, TeamEventType.AGENT_SELECTED, TeamRole.PLANNER, planner(team).agentId(),
            "模型选择 Executor " + executor.agentName() + "：" + selection.reason(), selection);

        boolean retrying = step.getRetryCount() > 0 || step.getStatus() == TeamStepStatus.RETRYING;
        step.setAssignedAgentId(executor.agentId());
        step.setStatus(retrying ? TeamStepStatus.RETRYING : TeamStepStatus.ASSIGNED);
        step.setStartedAt(Instant.now());
        stepRepository.save(step);
        recordEvent(runId, stepId, TeamEventType.STEP_ASSIGNED, TeamRole.EXECUTOR, executor.agentId(),
            "Step assigned to " + executor.agentName(), Map.of(
                "requiredCapabilities", requiredCapabilities,
                "selection", selection
            ));

        step.setStatus(retrying ? TeamStepStatus.RETRYING : TeamStepStatus.RUNNING);
        stepRepository.save(step);
        recordEvent(runId, stepId, retrying ? TeamEventType.STEP_RETRY_STARTED : TeamEventType.STEP_STARTED,
            TeamRole.EXECUTOR, executor.agentId(), "Executor started step", null);

        String prompt = executorPrompt(run, step);
        step.setInputJson(json.toJson(Map.of("prompt", prompt, "selection", selection)));
        stepRepository.save(step);

        PipelineContext result = executeTeamInternal(executor.agentId(), stepSession(run, step), prompt);
        String output = result.getFinalResponse();
        if (output != null && output.startsWith("[Error]")) {
            throw new IllegalStateException(output);
        }
        requireRunWritable(runId);
        step.setRawOutput(output);
        if (step.getRiskLevel() == TeamRiskLevel.HIGH) {
            reviewHighRiskStep(run, team, step, retrying);
            return;
        }
        completeStep(runId, step, executor.agentId(), retrying, TeamStepQualityStatus.PASSED);
    }

    private TeamAgentSelection selectExecutorWithModel(TeamRunEntity run, TeamSnapshot team, TeamStepEntity step,
                                                       List<String> requiredCapabilities,
                                                       List<TeamStepEntity> runSteps) {
        List<TeamAgentSelection> candidates = agentSelector.rankExecutors(team.members(), requiredCapabilities, runSteps);
        if (candidates.isEmpty()) {
            return null;
        }
        TeamAgentSelection fallback = candidates.get(0).withDecision("RULE_FALLBACK",
            "模型选择不可用时的规则兜底：" + candidates.get(0).reason());
        TeamMember planner = planner(team);
        String prompt = """
            你是 Agent Team 的 AgentSelector，由 Planner 负责为当前 Step 自主选择一个 Executor 成员。
            候选列表里的 memberKey 是唯一标识；如果多个候选使用同一个 agentId，必须用 memberKey 区分。
            规则评分只是参考，不要机械照抄；请结合用户任务、Step 描述、能力标签、依赖输出摘要、当前负载和验收标准决策。
            只能从 candidates 中选择一个 memberKey，不要编造候选。
            请只返回单行 JSON：
            {
              "memberKey": "候选 memberKey",
              "agentId": "候选 agentId",
              "reason": "中文说明为什么这个 Executor 最合适"
            }

            原始用户任务：
            %s

            当前 Step：
            %s

            Step 依赖输出摘要：
            %s

            Executor 候选：
            %s
            """.formatted(
            run.getTask(),
            json.toJson(Map.of(
                "stepId", step.getStepId(),
                "title", nullToBlank(step.getTitle()),
                "description", nullToBlank(step.getDescription()),
                "requiredCapabilities", requiredCapabilities,
                "acceptanceCriteria", nullToBlank(step.getAcceptanceCriteria()),
                "riskLevel", step.getRiskLevel() == null ? "" : step.getRiskLevel().name()
            )),
            abbreviate(dependencyOutputs(step), 1200),
            json.toJson(candidates)
        );
        try {
            PipelineContext result = executeTeamInternal(planner.agentId(),
                "team-run-" + run.getRunId() + "-agent-selector-" + step.getStepId() + "-" + UUID.randomUUID(),
                prompt);
            TeamExecutorSelectionDecision decision = json.parseExecutorSelectionDecision(
                result == null ? "" : result.getFinalResponse());
            TeamAgentSelection selected = agentSelector.matchModelDecision(candidates, decision.memberKey(),
                decision.agentId());
            if (selected != null) {
                return selected.withDecision("MODEL",
                    "模型自主选择：" + blankToDefault(decision.reason(), "匹配当前 Step 的语义和能力要求")
                        + "；" + selected.reason());
            }
            return fallback.withDecision("RULE_FALLBACK",
                "模型返回的 Executor 不在候选列表中，使用规则兜底；模型原始选择 memberKey="
                    + decision.memberKey() + "，agentId=" + decision.agentId() + "；" + fallback.reason());
        } catch (Exception e) {
            log.warn("AgentSelector model decision failed for run {} step {}: {}", run.getRunId(), step.getStepId(),
                e.getMessage());
            return fallback.withDecision("RULE_FALLBACK",
                "模型选择失败，使用规则兜底：" + e.getMessage() + "；" + fallback.reason());
        }
    }

    private void reviewHighRiskStep(TeamRunEntity run, TeamSnapshot team, TeamStepEntity step, boolean retrying) {
        TeamMember reviewer = subReviewer(team);
        recordEvent(run.getRunId(), step.getStepId(), TeamEventType.STEP_SUB_REVIEW_STARTED, TeamRole.SUB_REVIEWER,
            reviewer.agentId(), "SubReviewer 开始校验高风险 Step", null);
        ReviewerDecision decision = callSubReviewer(run, reviewer, step);
        step.setSubReviewJson(json.toJson(decision));
        step.setLastReviewReason(decision.reason());
        recordEvent(run.getRunId(), step.getStepId(), TeamEventType.STEP_SUB_REVIEWED, TeamRole.SUB_REVIEWER,
            reviewer.agentId(), decision.reason(), decision);

        if (decision.action() == ReviewerAction.CONTINUE) {
            completeStep(run.getRunId(), step, step.getAssignedAgentId(), retrying, TeamStepQualityStatus.PASSED);
            return;
        }
        if (decision.action() == ReviewerAction.ASK_CLARIFICATION) {
            pauseForClarification(run, decision, TeamClarificationStage.RESULT_REVIEW);
            return;
        }
        if (step.getRetryCount() >= runtimeProperties.getMaxStepRetries()) {
            step.setQualityStatus(TeamStepQualityStatus.FLAWED_ACCEPTED);
            step.setReviewStatus("FLAWED_ACCEPTED");
            step.setReflectionJson(json.toJson(reflectionForStep(step, decision)));
            completeStep(run.getRunId(), step, step.getAssignedAgentId(), retrying, TeamStepQualityStatus.FLAWED_ACCEPTED);
            return;
        }
        prepareStepRetry(run, step, decision, reviewer);
    }

    private ReviewerDecision callSubReviewer(TeamRunEntity run, TeamMember reviewer, TeamStepEntity step) {
        String prompt = """
            你是 Agent Team 的 SubReviewer，负责审查单个高风险 Step 的原始输出。
            请对照原始任务、Step 描述和验收标准，判断该 Step 是否可被下游依赖使用。
            如果需要重试，必须给出可执行的 Reflexion 修改意见。
            请只返回 JSON：
            {
              "action": "CONTINUE | RETRY | ASK_CLARIFICATION",
              "reason": "中文审查原因",
              "questions": ["需要用户补充时填写"],
              "retryStepIds": [],
              "revisionInstructions": "重试修改意见",
              "stepReflections": {
                "%s": {
                  "failedCriteria": ["未满足的验收标准"],
                  "reviewReason": "失败原因",
                  "revisionInstructions": "具体修正建议",
                  "previousOutputSummary": "上一轮输出摘要",
                  "avoidRepeating": ["避免重复的问题"]
                }
              },
              "finalReport": ""
            }

            原始用户任务：
            %s

            Step 标题：
            %s

            Step 描述：
            %s

            验收标准：
            %s

            风险原因：
            %s

            Executor 原始输出：
            %s
            """.formatted(
            step.getStepId(),
            run.getTask(),
            step.getTitle(),
            nullToBlank(step.getDescription()),
            nullToBlank(step.getAcceptanceCriteria()),
            nullToBlank(step.getRiskReason()),
            nullToBlank(step.getRawOutput())
        );
        return executeReviewerDecision(reviewer, run, prompt, false);
    }

    private void completeStep(String runId, TeamStepEntity step, String actorAgentId, boolean retrying,
                              TeamStepQualityStatus qualityStatus) {
        step.setQualityStatus(qualityStatus);
        step.setReviewStatus(qualityStatus.name());
        step.setStatus(TeamStepStatus.COMPLETED);
        step.setCompletedAt(Instant.now());
        stepRepository.save(step);
        recordEvent(runId, step.getStepId(), retrying ? TeamEventType.STEP_RETRY_COMPLETED : TeamEventType.STEP_COMPLETED,
            TeamRole.EXECUTOR, actorAgentId, "Executor 完成 Step", Map.of("qualityStatus", qualityStatus.name()));
    }

    private String executorPrompt(TeamRunEntity run, TeamStepEntity step) {
        return """
            You are an Executor in an Agent Team. Complete only the assigned step.
            Use available tools when useful. Return raw findings clearly, not the final report.
            Your answer must be the actual deliverable for this step. Do not return raw tool call markup, DSML tags, JSON tool calls, or a list of search queries.
            Write the deliverable in Simplified Chinese.

            Original user task:
            %s

            User clarification, if any:
            %s

            Step title:
            %s

            Step description:
            %s

            Acceptance criteria:
            %s

            Dependency outputs, if any:
            %s

            Previous output, if retrying:
            %s

            Reviewer revision instructions, if any:
            %s

            Reflexion context, if retrying:
            %s
            """.formatted(
            run.getTask(),
            nullToBlank(run.getClarificationAnswer()),
            step.getTitle(),
            nullToBlank(step.getDescription()),
            nullToBlank(step.getAcceptanceCriteria()),
            dependencyOutputs(step),
            nullToBlank(step.getPreviousOutputsJson()),
            nullToBlank(step.getRevisionInstructions()),
            nullToBlank(step.getReflectionJson())
        );
    }

    private void mergeAndReviewResults(TeamRunEntity run, TeamSnapshot team) {
        run.setStatus(TeamRunStatus.MERGING);
        runRepository.save(run);
        TeamMember merger = merger(team);
        recordEvent(run.getRunId(), null, TeamEventType.MERGE_STARTED, TeamRole.MERGER,
            merger.agentId(), "MergeAgent 开始聚合 Step 输出", null);
        String mergeOutput = callMerger(run, merger);
        run.setMergeOutput(mergeOutput);
        runRepository.save(run);
        recordEvent(run.getRunId(), null, TeamEventType.MERGE_COMPLETED, TeamRole.MERGER,
            merger.agentId(), "MergeAgent 完成聚合", Map.of("mergeOutput", mergeOutput));
        TeamConflictReport conflictReport = detectConflicts(run, team, mergeOutput);
        run.setConflictReportJson(json.toJson(conflictReport));
        runRepository.save(run);
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
            runRepository.save(run);
            recordEvent(run.getRunId(), null, TeamEventType.ARBITRATION_COMPLETED, TeamRole.PLANNER,
                planner(team).agentId(), "Planner 完成冲突仲裁", run.getArbitrationJson());
            recordEvent(run.getRunId(), null, TeamEventType.MERGE_STARTED, TeamRole.MERGER,
                merger.agentId(), "MergeAgent 带 Planner 仲裁结果二次聚合", null);
            mergeOutput = callMerger(run, merger);
            run.setMergeOutput(mergeOutput);
            TeamConflictReport secondReport = detectConflicts(run, team, mergeOutput);
            run.setConflictReportJson(json.toJson(secondReport));
            runRepository.save(run);
            recordEvent(run.getRunId(), null, TeamEventType.MERGE_COMPLETED, TeamRole.MERGER,
                merger.agentId(), "MergeAgent 完成仲裁后二次聚合", Map.of("mergeOutput", mergeOutput));
            recordEvent(run.getRunId(), null, TeamEventType.CONFLICT_DETECTED, null, null,
                secondReport.hasConflict() ? "ConflictDetector 二次检测仍有冲突" : "ConflictDetector 二次检测无冲突", secondReport);
        }
        reviewResults(run, team);
    }

    private String callMerger(TeamRunEntity run, TeamMember merger) {
        List<TeamStepSnapshot> steps = stepRepository.findByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
            .map(this::toStepSnapshot)
            .toList();
        String prompt = """
            你是 Agent Team 的 MergeAgent，负责把多个 Step 的原始结果聚合成一致、可读、可审查的中文草稿。
            请统一口径、指出冲突或瑕疵，不要隐藏 FLAWED_ACCEPTED 的问题。
            忽略状态为 SUPERSEDED 的旧 Step，它们只作为重规划历史。
            如果存在 Planner 仲裁结果，请按仲裁口径统一数字、日期、地点和执行方案。
            只输出聚合后的 Markdown 草稿，不要输出 JSON。

            原始用户任务：
            %s

            Step 结果：
            %s

            ConflictDetector 报告：
            %s

            Planner 仲裁结果：
            %s
            """.formatted(
            run.getTask(),
            json.toJson(steps),
            nullToBlank(run.getConflictReportJson()),
            nullToBlank(run.getArbitrationJson())
        );
        PipelineContext result = executeTeamInternal(merger.agentId(), "team-run-" + run.getRunId() + "-merger", prompt);
        String output = result == null ? "" : result.getFinalResponse();
        return output == null || output.isBlank() ? synthesizeFallbackReport(run) : output;
    }

    private TeamConflictReport detectConflicts(TeamRunEntity run, TeamSnapshot team, String mergeOutput) {
        TeamMember reviewer = reviewer(team);
        List<TeamStepSnapshot> steps = stepRepository.findByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
            .map(this::toStepSnapshot)
            .toList();
        String prompt = """
            你是 Agent Team 的 ConflictDetector，负责在 MergeAgent 聚合后检测事实冲突和口径不一致。
            只判断已经完成且未被 SUPERSEDED 的 Step 与 Merge 草稿之间是否存在冲突。
            重点检查：预算金额、日期时间、地点、人数、天气风险、责任人、前后步骤依赖口径。
            请只返回单行 JSON：
            {
              "hasConflict": true,
              "conflictFields": ["冲突字段"],
              "affectedStepIds": ["涉及 Step ID"],
              "reason": "中文冲突说明",
              "normalizationAdvice": "建议统一口径"
            }

            原始用户任务：
            %s

            Step 结果：
            %s

            MergeAgent 草稿：
            %s
            """.formatted(run.getTask(), json.toJson(steps), nullToBlank(mergeOutput));
        try {
            PipelineContext result = executeTeamInternal(reviewer.agentId(),
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
        List<TeamStepSnapshot> steps = stepRepository.findByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
            .map(this::toStepSnapshot)
            .toList();
        String prompt = """
            你是 PlannerArbitration，负责根据冲突报告给出唯一执行口径。
            请不要重写最终报告，只输出仲裁意见和统一规范。
            输出中文 Markdown，包含：冲突结论、统一口径、MergeAgent 二次聚合注意事项。

            原始用户任务：
            %s

            ConflictDetector 报告：
            %s

            Step 结果：
            %s

            当前 Merge 草稿：
            %s
            """.formatted(
            run.getTask(),
            json.toJson(conflictReport),
            json.toJson(steps),
            nullToBlank(run.getMergeOutput())
        );
        PipelineContext result = executeTeamInternal(planner.agentId(),
            "team-run-" + run.getRunId() + "-planner-arbitration-" + UUID.randomUUID(), prompt);
        String output = result == null ? "" : result.getFinalResponse();
        return output == null || output.isBlank() ? "未生成仲裁意见，GlobalReviewer 继续兜底终审。" : output;
    }

    private void reviewResults(TeamRunEntity run, TeamSnapshot team) {
        run.setStatus(TeamRunStatus.GLOBAL_REVIEWING);
        runRepository.save(run);
        recordEvent(run.getRunId(), null, TeamEventType.GLOBAL_REVIEW_STARTED, TeamRole.REVIEWER,
            reviewer(team).agentId(), "GlobalReviewer 开始终审", null);

        ReviewerDecision decision = callReviewerForResults(run, team);
        requireRunWritable(run.getRunId());
        run.setResultReviewJson(json.toJson(decision));
        run.setGlobalReviewJson(json.toJson(decision));
        runRepository.save(run);
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
            runRepository.save(run);
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
        if (run.getResultReplanCount() >= runtimeProperties.getMaxResultReplans()) {
            failRun(run.getRunId(), "Reviewer requested replan but result replan limit reached: " + decision.reason());
            return;
        }

        List<TeamStepSnapshot> previousResults = stepRepository.findByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
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
        runRepository.save(run);
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
        if (run.getPartialReplanCount() >= runtimeProperties.getMaxResultReplans()) {
            failRun(run.getRunId(), "Reviewer requested partial replan but partial replan limit reached: " + decision.reason());
            return;
        }
        List<TeamStepEntity> all = stepRepository.findByRunIdOrderByStepIndexAsc(run.getRunId());
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
        runRepository.save(run);
        recordEvent(run.getRunId(), null, TeamEventType.REPLAN_REQUESTED, TeamRole.REVIEWER,
            reviewer(team).agentId(), "GlobalReviewer requested partial DAG replan: " + decision.reason(), decision);

        for (TeamStepEntity step : all) {
            if (affected.contains(step.getStepId())) {
                step.setStatus(TeamStepStatus.SUPERSEDED);
                step.setRevisionInstructions(decision.revisionInstructions());
                step.setLastReviewReason(decision.reason());
                step.setReflectionJson(json.toJson(reflectionForStep(step, decision)));
                stepRepository.save(step);
                recordEvent(run.getRunId(), step.getStepId(), TeamEventType.STEP_REFLECTION_RECORDED,
                    TeamRole.REVIEWER, reviewer(team).agentId(), "局部重规划写入 Reflexion 上下文", step.getReflectionJson());
            }
        }

        run.setStatus(TeamRunStatus.PLANNING);
        runRepository.save(run);
        recordEvent(run.getRunId(), null, TeamEventType.PLAN_STARTED, TeamRole.PLANNER,
            planner(team).agentId(), "Planner started partial DAG replan", Map.of("affectedStepIds", affected));
        List<PlannedStep> planned = callPlanner(run, team, affectedPlan, decision.revisionInstructions(), previousResults);
        appendPartialReplannedSteps(run, planned);
        recordEvent(run.getRunId(), null, TeamEventType.PLAN_CREATED, TeamRole.PLANNER,
            planner(team).agentId(), "Planner created " + planned.size() + " replacement step(s)", planned);

        run.setStatus(TeamRunStatus.PLAN_REVIEWING);
        runRepository.save(run);
        ReviewerDecision planDecision = callReviewerForPlan(run, team, planned);
        run.setPlanReviewJson(json.toJson(planDecision));
        runRepository.save(run);
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
        List<TeamStepSnapshot> steps = stepRepository.findByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
            .map(this::toStepSnapshot)
            .toList();
        String prompt = """
            You are the Reviewer, quality gate, and final report writer for an Agent Team.
            You are acting as GlobalReviewer. Compare the original user request, MergeAgent draft, and every Executor result.
            Write reason, questions, revisionInstructions, and finalReport in Simplified Chinese.
            If the planned Step structure is still correct but some results are insufficient, return RETRY with the exact retryStepIds and revision instructions.
            If only a local DAG branch needs replanning, return PARTIAL_REPLAN with affectedStepIds and revisionInstructions.
            If the Step structure itself is wrong or missing required work globally, return REPLAN with revisionInstructions for the Planner.
            If user input is ambiguous, return ASK_CLARIFICATION with questions.
            If the result is unrecoverable within the current limits, return FAILED.
            If acceptable, return CONTINUE and write finalReport as a complete user-facing report.
            Any RETRY or PARTIAL_REPLAN must include Reflexion in stepReflections for affected Step IDs.
            Respond as one single-line valid JSON object only.
            Text fields must not contain raw line breaks. Encode line breaks as \\n only when needed.
            Do not use ASCII double quotes inside text values; use Chinese corner quotes 「」 instead.
            Respond only with JSON:
            {
              "action": "CONTINUE | RETRY | PARTIAL_REPLAN | REPLAN | ASK_CLARIFICATION | FAILED",
              "reason": "why",
              "questions": ["only if clarification is needed"],
              "retryStepIds": ["step-id only when action is RETRY"],
              "affectedStepIds": ["step-id only when action is PARTIAL_REPLAN"],
              "revisionInstructions": "instructions for Executor retry or Planner replan",
              "stepReflections": {
                "step-id": {
                  "failedCriteria": ["unmet criteria"],
                  "reviewReason": "why this step failed",
                  "revisionInstructions": "how to fix it",
                  "previousOutputSummary": "summary of bad output",
                  "avoidRepeating": ["mistakes to avoid"]
                }
              },
              "finalReport": "complete final report when action is CONTINUE"
            }

            Original task:
            %s

            User clarification, if any:
            %s

            MergeAgent draft:
            %s

            ConflictDetector report:
            %s

            Planner arbitration, if any:
            %s

            Steps and raw executor outputs:
            %s
            """.formatted(
            run.getTask(),
            nullToBlank(run.getClarificationAnswer()),
            nullToBlank(run.getMergeOutput()),
            nullToBlank(run.getConflictReportJson()),
            nullToBlank(run.getArbitrationJson()),
            json.toJson(steps)
        );

        return executeReviewerDecision(reviewer, run, prompt, true);
    }

    ReviewerDecision executeReviewerDecision(TeamMember reviewer, TeamRunEntity run,
                                             String prompt, boolean requireFinalReport) {
        String raw = executeTeamInternal(reviewer.agentId(), reviewerSession(run), prompt).getFinalResponse();
        for (int attempt = 0; attempt <= runtimeProperties.getMaxReviewerFormatRepairs(); attempt++) {
            try {
                ReviewerDecision decision = json.parseDecision(raw);
                validateReviewerDecision(decision, requireFinalReport);
                return decision;
            } catch (IllegalArgumentException e) {
                if (attempt >= runtimeProperties.getMaxReviewerFormatRepairs()) {
                    throw e;
                }
                raw = repairReviewerDecisionJson(reviewer, run, prompt, raw, e.getMessage());
            }
        }
        throw new IllegalStateException("Reviewer decision repair exhausted");
    }

    private String repairReviewerDecisionJson(TeamMember reviewer, TeamRunEntity run,
                                              String originalPrompt, String invalidResponse, String parseError) {
        String repairPrompt = """
            Your previous Reviewer response was rejected because it was not valid JSON.
            Re-evaluate the original Reviewer instruction and return a corrected decision as JSON only.
            Do not copy the invalid response text.
            Return one single-line valid JSON object only.
            Do not add markdown, comments, or prose.
            Text fields must not contain raw line breaks. Encode line breaks as \\n only when needed.
            Do not use ASCII double quotes inside text values; use Chinese corner quotes 「」 instead.
            Required schema:
            {
              "action": "CONTINUE | RETRY | PARTIAL_REPLAN | REPLAN | ASK_CLARIFICATION | FAILED",
              "reason": "why",
              "questions": [],
              "retryStepIds": [],
              "affectedStepIds": [],
              "revisionInstructions": "",
              "stepReflections": {},
              "finalReport": ""
            }

            Parse error:
            %s

            Original Reviewer instruction:
            %s

            Invalid response excerpt, do not copy:
            %s
            """.formatted(nullToBlank(parseError), originalPrompt, abbreviate(nullToBlank(invalidResponse), 1200));

        return executeTeamInternal(reviewer.agentId(), reviewerSession(run) + "-repair-" + UUID.randomUUID(), repairPrompt)
            .getFinalResponse();
    }

    private PipelineContext executeTeamInternal(String agentId, String sessionId, String prompt) {
        return orchestrator.executeInternal(agentId, sessionId, prompt);
    }

    private void requireRunWritable(String runId) {
        if (isRunDeleted(runId)) {
            throw new IllegalStateException("Run was deleted: " + runId);
        }
    }

    private boolean isRunDeleted(String runId) {
        return deletedRunIds.contains(runId) || !runRepository.existsById(runId);
    }

    private List<TeamStepEntity> retryableSteps(String runId, List<String> retryStepIds) {
        List<TeamStepEntity> all = stepRepository.findByRunIdOrderByStepIndexAsc(runId);
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
        stepRepository.save(step);
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
        for (TeamStepEntity dependency : stepRepository.findByRunIdOrderByStepIndexAsc(step.getRunId())) {
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

    private void pauseForClarification(TeamRunEntity run, ReviewerDecision decision, TeamClarificationStage stage) {
        String question = decision.questions().isEmpty() ? decision.reason() : String.join("\n", decision.questions());
        run.setClarificationQuestion(question);
        run.setClarificationStage(stage);
        run.setStatus(TeamRunStatus.NEEDS_CLARIFICATION);
        runRepository.save(run);
        recordEvent(run.getRunId(), null, TeamEventType.CLARIFICATION_REQUESTED, TeamRole.REVIEWER,
            null, question, decision);
    }

    private void prepareStepsForClarificationResume(TeamRunEntity run) {
        for (TeamStepEntity step : stepRepository.findByRunIdOrderByStepIndexAsc(run.getRunId())) {
            if (step.getStatus() == TeamStepStatus.FAILED) {
                continue;
            }
            List<String> previous = new ArrayList<>(json.stringList(step.getPreviousOutputsJson()));
            if (step.getRawOutput() != null && !step.getRawOutput().isBlank()) {
                previous.add(step.getRawOutput());
            }
            step.setPreviousOutputsJson(json.toJson(previous));
            step.setRevisionInstructions("User clarification: " + run.getClarificationAnswer());
            step.setStatus(TeamStepStatus.RETRYING);
            step.setQualityStatus(TeamStepQualityStatus.RETRY_REQUESTED);
            step.setStartedAt(null);
            step.setCompletedAt(null);
            stepRepository.save(step);
        }
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
        TeamStepEntity step = stepRepository.findById(stepId).orElse(null);
        if (step != null) {
            step.setStatus(TeamStepStatus.FAILED);
            step.setRawOutput("[Error] " + nullToBlank(message));
            step.setCompletedAt(Instant.now());
            stepRepository.save(step);
        }
        recordEvent(runId, stepId, TeamEventType.STEP_FAILED, TeamRole.EXECUTOR, null,
            "Step failed: " + nullToBlank(message), null);
    }

    @Transactional
    protected void markStepTimedOut(String runId, String stepId) {
        String message = "Step execution timed out after " + runtimeProperties.getStepTimeoutSeconds() + " seconds";
        TeamStepEntity step = stepRepository.findById(stepId).orElse(null);
        if (step != null && step.getStatus() != TeamStepStatus.COMPLETED) {
            step.setStatus(TeamStepStatus.FAILED);
            step.setRawOutput("[Error] " + message);
            step.setCompletedAt(Instant.now());
            stepRepository.save(step);
        }
        recordEvent(runId, stepId, TeamEventType.STEP_TIMEOUT, TeamRole.EXECUTOR, null,
            "Step 超时熔断: " + message, Map.of("stepTimeoutSeconds", runtimeProperties.getStepTimeoutSeconds()));
    }

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
        TeamRunEntity run = runRepository.findById(runId).orElse(null);
        if (run != null) {
            run.setStatus(TeamRunStatus.FAILED);
            run.setFinalOutput("[Error] " + nullToBlank(message));
            runRepository.save(run);
        }
        recordEvent(runId, null, TeamEventType.RUN_FAILED, null, null,
            "Run failed: " + nullToBlank(message), null);
    }

    private void replaceSteps(TeamRunEntity run, List<PlannedStep> planned) {
        replaceSteps(run, planned, false);
    }

    private void replaceSteps(TeamRunEntity run, List<PlannedStep> planned, boolean preserveExisting) {
        int baseIndex = 0;
        if (preserveExisting) {
            for (TeamStepEntity existing : stepRepository.findByRunIdOrderByStepIndexAsc(run.getRunId())) {
                if (existing.getStatus() != TeamStepStatus.SUPERSEDED) {
                    existing.setStatus(TeamStepStatus.SUPERSEDED);
                    stepRepository.save(existing);
                }
                baseIndex = Math.max(baseIndex, existing.getStepIndex());
            }
        } else {
            stepRepository.deleteByRunId(run.getRunId());
        }
        List<PlannedStep> executable = planned.stream()
            .filter(item -> !isFinalIntegrationStep(item))
            .toList();
        validatePlannedDag(executable);
        boolean simpleDraft = run.getTaskLevel() == TeamTaskLevel.SIMPLE && executable.size() == 1;

        Map<String, String> clientToStepId = new LinkedHashMap<>();
        int index = 1;
        for (PlannedStep item : executable) {
            String clientStepId = simpleDraft ? "simple-draft" : clientStepId(item, index);
            clientToStepId.put(clientStepId, "step-" + index + "-" + UUID.randomUUID().toString().substring(0, 8));
            index++;
        }

        index = 1;
        for (PlannedStep item : executable) {
            String clientStepId = simpleDraft ? "simple-draft" : clientStepId(item, index);
            List<String> dependencyStepIds = item.dependsOn().stream()
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
            step.setPlanIteration(run.getFullReplanCount() + run.getPartialReplanCount() + run.getResultReplanCount());
            stepRepository.save(step);
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
        List<TeamStepEntity> existingSteps = stepRepository.findByRunIdOrderByStepIndexAsc(run.getRunId());
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
            stepRepository.save(step);
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
        eventRepository.save(event);
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
        TeamRunEntity run = runRepository.findById(runId)
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
        List<TeamMember> members = memberRepository.findByTeamIdOrderBySortOrderAscIdAsc(team.getTeamId()).stream()
            .map(this::toMember)
            .toList();
        return new TeamSnapshot(team.getTeamId(), team.getName(), members, team.getCreatedAt(), team.getUpdatedAt());
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
        List<TeamStepSnapshot> steps = includeSteps
            ? stepRepository.findByRunIdOrderByStepIndexAsc(run.getRunId()).stream().map(this::toStepSnapshot).toList()
            : List.of();
        List<TeamEventSnapshot> events = includeEvents
            ? eventRepository.findByRunIdOrderByCreatedAtAscIdAsc(run.getRunId()).stream().map(this::toEventSnapshot).toList()
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

    private String executorCapabilities(TeamSnapshot team) {
        List<Map<String, Object>> capabilities = new ArrayList<>();
        for (TeamMember member : team.members()) {
            if (member.role() == TeamRole.EXECUTOR) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("memberKey", member.agentId() + "#" + member.sortOrder());
                item.put("agentId", member.agentId());
                item.put("agentName", member.agentName());
                item.put("capabilityTags", member.capabilityTags());
                item.put("sortOrder", member.sortOrder());
                capabilities.add(item);
            }
        }
        return json.toJson(capabilities);
    }

    private String synthesizeFallbackReport(TeamRunEntity run) {
        StringBuilder output = new StringBuilder();
        output.append("任务完成。\n\n");
        for (TeamStepEntity step : stepRepository.findByRunIdOrderByStepIndexAsc(run.getRunId())) {
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

    private String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value == null ? "" : value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private String normalizeUserId(String userId) {
        return userId == null || userId.isBlank() ? "default" : userId;
    }
}
