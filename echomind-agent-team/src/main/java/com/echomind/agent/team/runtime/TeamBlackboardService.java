package com.echomind.agent.team.runtime;

import com.echomind.agent.Agent;
import com.echomind.agent.AgentFactory;
import com.echomind.agent.orchestration.AgentOrchestrator;
import com.echomind.agent.pipeline.PipelineContext;
import com.echomind.agent.team.model.PlannedStep;
import com.echomind.agent.team.model.ReviewerDecision;
import com.echomind.agent.team.model.TeamMember;
import com.echomind.agent.team.state.ReviewerAction;
import com.echomind.agent.team.state.TeamClarificationStage;
import com.echomind.agent.team.state.TeamEventType;
import com.echomind.agent.team.state.TeamRole;
import com.echomind.agent.team.state.TeamRunStatus;
import com.echomind.agent.team.state.TeamStepStatus;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent Team 的 MySQL 黑板和异步状态机服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeamBlackboardService {

    private static final int MAX_PLAN_RETRIES = 1;
    /** 每个 Step 最多允许 Reviewer 触发 2 次重试。 */
    private static final int MAX_STEP_RETRIES = 2;
    private static final int MAX_REVIEWER_FORMAT_REPAIRS = 1;

    private final TeamRepository teamRepository;
    private final TeamMemberRepository memberRepository;
    private final TeamRunRepository runRepository;
    private final TeamStepRepository stepRepository;
    private final TeamEventRepository eventRepository;
    private final AgentFactory agentFactory;
    private final AgentOrchestrator orchestrator;
    private final TaskExecutor taskExecutor;

    private final TeamJsonSupport json = new TeamJsonSupport();
    private final TeamCapabilityMatcher capabilityMatcher = new TeamCapabilityMatcher();
    private final Set<String> deletedRunIds = ConcurrentHashMap.newKeySet();

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
        if (task == null || task.isBlank()) {
            throw new IllegalArgumentException("task is required");
        }
        getTeam(teamId);
        TeamRunEntity run = new TeamRunEntity();
        run.setRunId(UUID.randomUUID().toString());
        run.setTeamId(teamId);
        run.setTask(task.trim());
        run.setStatus(TeamRunStatus.PENDING);
        runRepository.save(run);
        recordEvent(run.getRunId(), null, TeamEventType.RUN_CREATED, null, null, "Run created", null);
        scheduleRun(run.getRunId());
        return getRun(teamId, run.getRunId());
    }

    @Transactional(readOnly = true)
    public List<TeamRunSnapshot> listRuns(String teamId) {
        getTeam(teamId);
        return runRepository.findByTeamIdOrderByCreatedAtDesc(teamId).stream()
            .map(run -> toRunSnapshot(run, true, false))
            .toList();
    }

    @Transactional(readOnly = true)
    public TeamRunSnapshot getRun(String teamId, String runId) {
        TeamRunEntity run = loadRun(teamId, runId);
        return toRunSnapshot(run, true, true);
    }

    @Transactional
    public TeamRunSnapshot resumeRun(String teamId, String runId, String clarificationAnswer) {
        TeamRunEntity run = loadRun(teamId, runId);
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
        return getRun(teamId, runId);
    }

    public TeamRunSnapshot executeRunBlocking(String teamId, String task) {
        TeamRunSnapshot created = createRun(teamId, task);
        while (true) {
            TeamRunSnapshot current = getRun(teamId, created.runId());
            if (isTerminal(current.status())) {
                return current;
            }
            try {
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while waiting for team run", e);
            }
        }
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

        List<TeamStepEntity> existingSteps = stepRepository.findByRunIdOrderByStepIndexAsc(runId);
        if (existingSteps.isEmpty()) {
            planAndReview(run, team);
            if (run.getStatus() == TeamRunStatus.NEEDS_CLARIFICATION || run.getStatus() == TeamRunStatus.FAILED) {
                return;
            }
        }

        executePendingSteps(run, team);
        if (run.getStatus() == TeamRunStatus.FAILED) {
            return;
        }

        reviewResults(run, team);
    }

    private void planAndReview(TeamRunEntity run, TeamSnapshot team) {
        boolean approved = false;
        List<PlannedStep> previousPlan = List.of();
        String plannerRevisionInstructions = "";
        while (!approved && run.getPlanRetryCount() <= MAX_PLAN_RETRIES) {
            run.setStatus(TeamRunStatus.PLANNING);
            runRepository.save(run);
            recordEvent(run.getRunId(), null, TeamEventType.PLAN_STARTED, TeamRole.PLANNER,
                planner(team).agentId(), "Planner started task decomposition", null);

            List<PlannedStep> planned = callPlanner(run, team, previousPlan, plannerRevisionInstructions);
            requireRunWritable(run.getRunId());
            replaceSteps(run, planned);
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
            } else if (run.getPlanRetryCount() >= MAX_PLAN_RETRIES) {
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
                                          String plannerRevisionInstructions) {
        TeamMember planner = planner(team);
        String prompt = buildPlannerPrompt(run, team, previousPlan, plannerRevisionInstructions);
        PipelineContext result = executeTeamInternal(planner.agentId(), plannerSession(run), prompt);
        List<PlannedStep> steps = json.parsePlan(result.getFinalResponse());
        if (steps.isEmpty()) {
            throw new IllegalStateException("Planner did not produce any steps");
        }
        return steps;
    }

    String buildPlannerPrompt(TeamRunEntity run, TeamSnapshot team, List<PlannedStep> previousPlan,
                              String plannerRevisionInstructions) {
        return """
            You are the Planner in an Agent Team. Decompose the user's task into 2-5 executable subtasks.
            Write all user-visible text in Simplified Chinese.
            Respond only with JSON:
            {
              "steps": [
                {
                  "title": "short title",
                  "description": "specific executor task",
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

            Available executor capabilities:
            %s
            """.formatted(
            run.getTask(),
            nullToBlank(run.getClarificationAnswer()),
            json.toJson(previousPlan == null ? List.of() : previousPlan),
            nullToBlank(plannerRevisionInstructions),
            executorCapabilities(team)
        );
    }

    private ReviewerDecision callReviewerForPlan(TeamRunEntity run, TeamSnapshot team, List<PlannedStep> planned) {
        TeamMember reviewer = reviewer(team);
        String prompt = """
            You are the Reviewer and quality gate for an Agent Team.
            Review whether the Planner's subtasks cover the original user need, are actionable, and have clear capabilities.
            Write reason, questions, revisionInstructions, and finalReport in Simplified Chinese.
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

    private void executePendingSteps(TeamRunEntity run, TeamSnapshot team) {
        run.setStatus(TeamRunStatus.EXECUTING);
        runRepository.save(run);
        List<TeamStepEntity> steps = stepRepository.findByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
            .filter(step -> step.getStatus() == TeamStepStatus.PENDING || step.getStatus() == TeamStepStatus.RETRYING)
            .toList();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (TeamStepEntity step : steps) {
            futures.add(CompletableFuture.runAsync(() -> executeStepSafely(run.getRunId(), step.getStepId()), taskExecutor));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private void executeStepSafely(String runId, String stepId) {
        try {
            executeStep(runId, stepId);
        } catch (Exception e) {
            log.error("Team step failed: {}", stepId, e);
            markStepFailed(runId, stepId, e.getMessage());
        }
    }

    @Transactional
    protected void executeStep(String runId, String stepId) {
        TeamRunEntity run = runRepository.findById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        TeamSnapshot team = getTeam(run.getTeamId());
        TeamStepEntity step = stepRepository.findById(stepId)
            .orElseThrow(() -> new IllegalArgumentException("Step not found: " + stepId));

        TeamMember executor = capabilityMatcher.selectExecutor(team.members(), json.stringList(step.getRequiredCapabilitiesJson()));
        if (executor == null) {
            throw new IllegalStateException("No executor configured");
        }

        boolean retrying = step.getRetryCount() > 0 || step.getStatus() == TeamStepStatus.RETRYING;
        step.setAssignedAgentId(executor.agentId());
        step.setStatus(retrying ? TeamStepStatus.RETRYING : TeamStepStatus.ASSIGNED);
        step.setStartedAt(Instant.now());
        stepRepository.save(step);
        recordEvent(runId, stepId, TeamEventType.STEP_ASSIGNED, TeamRole.EXECUTOR, executor.agentId(),
            "Step assigned to " + executor.agentName(), Map.of("requiredCapabilities", json.stringList(step.getRequiredCapabilitiesJson())));

        step.setStatus(retrying ? TeamStepStatus.RETRYING : TeamStepStatus.RUNNING);
        stepRepository.save(step);
        recordEvent(runId, stepId, retrying ? TeamEventType.STEP_RETRY_STARTED : TeamEventType.STEP_STARTED,
            TeamRole.EXECUTOR, executor.agentId(), "Executor started step", null);

        String prompt = executorPrompt(run, step);
        step.setInputJson(json.toJson(Map.of("prompt", prompt)));
        stepRepository.save(step);

        PipelineContext result = executeTeamInternal(executor.agentId(), stepSession(run, step), prompt);
        String output = result.getFinalResponse();
        if (output != null && output.startsWith("[Error]")) {
            throw new IllegalStateException(output);
        }
        requireRunWritable(runId);
        step.setRawOutput(output);
        step.setStatus(TeamStepStatus.COMPLETED);
        step.setCompletedAt(Instant.now());
        stepRepository.save(step);
        recordEvent(runId, stepId, retrying ? TeamEventType.STEP_RETRY_COMPLETED : TeamEventType.STEP_COMPLETED,
            TeamRole.EXECUTOR, executor.agentId(), "Executor completed step", null);
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

            Previous output, if retrying:
            %s

            Reviewer revision instructions, if any:
            %s
            """.formatted(
            run.getTask(),
            nullToBlank(run.getClarificationAnswer()),
            step.getTitle(),
            nullToBlank(step.getDescription()),
            nullToBlank(step.getAcceptanceCriteria()),
            nullToBlank(step.getPreviousOutputsJson()),
            nullToBlank(step.getRevisionInstructions())
        );
    }

    private void reviewResults(TeamRunEntity run, TeamSnapshot team) {
        run.setStatus(TeamRunStatus.RESULT_REVIEWING);
        runRepository.save(run);
        recordEvent(run.getRunId(), null, TeamEventType.RESULT_REVIEW_STARTED, TeamRole.REVIEWER,
            reviewer(team).agentId(), "Reviewer started result review", null);

        ReviewerDecision decision = callReviewerForResults(run, team);
        requireRunWritable(run.getRunId());
        run.setResultReviewJson(json.toJson(decision));
        runRepository.save(run);
        recordEvent(run.getRunId(), null, TeamEventType.RESULT_REVIEWED, TeamRole.REVIEWER,
            reviewer(team).agentId(), decision.reason(), decision);

        if (decision.action() == ReviewerAction.CONTINUE) {
            run.setFinalOutput(decision.finalReport().isBlank() ? synthesizeFallbackReport(run) : decision.finalReport());
            run.setStatus(TeamRunStatus.COMPLETED);
            run.setMermaidDiagram(MermaidGenerator.generateFromSnapshots(
                getTeam(run.getTeamId()).name(),
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

        List<TeamStepEntity> retrySteps = retryableSteps(run.getRunId(), decision.retryStepIds());
        if (retrySteps.isEmpty()) {
            failRun(run.getRunId(), "Reviewer requested retry but no retryable steps remained: " + decision.reason());
            return;
        }
        for (TeamStepEntity step : retrySteps) {
            List<String> previous = new ArrayList<>(json.stringList(step.getPreviousOutputsJson()));
            if (step.getRawOutput() != null && !step.getRawOutput().isBlank()) {
                previous.add(step.getRawOutput());
            }
            step.setPreviousOutputsJson(json.toJson(previous));
            step.setRevisionInstructions(decision.revisionInstructions());
            step.setRetryCount(step.getRetryCount() + 1);
            step.setStatus(TeamStepStatus.RETRYING);
            step.setReviewStatus("RETRY_REQUESTED");
            step.setStartedAt(null);
            step.setCompletedAt(null);
            stepRepository.save(step);
            recordEvent(run.getRunId(), step.getStepId(), TeamEventType.RETRY_REQUESTED, TeamRole.REVIEWER,
                reviewer(team).agentId(), decision.reason(), decision);
        }
        executePendingSteps(run, team);
        reviewResults(run, team);
    }

    private ReviewerDecision callReviewerForResults(TeamRunEntity run, TeamSnapshot team) {
        TeamMember reviewer = reviewer(team);
        List<TeamStepSnapshot> steps = stepRepository.findByRunIdOrderByStepIndexAsc(run.getRunId()).stream()
            .map(this::toStepSnapshot)
            .toList();
        String prompt = """
            You are the Reviewer, quality gate, and final report writer for an Agent Team.
            Compare the original user request with every Executor result.
            Write reason, questions, revisionInstructions, and finalReport in Simplified Chinese.
            If some results are insufficient, return RETRY with the exact retryStepIds and revision instructions.
            If user input is ambiguous, return ASK_CLARIFICATION with questions.
            If acceptable, return CONTINUE and write finalReport as a complete user-facing report.
            Respond only with JSON:
            {
              "action": "CONTINUE | RETRY | ASK_CLARIFICATION",
              "reason": "why",
              "questions": ["only if clarification is needed"],
              "retryStepIds": ["step-id"],
              "revisionInstructions": "instructions for Executor if retry is needed",
              "finalReport": "complete final report when action is CONTINUE"
            }

            Original task:
            %s

            User clarification, if any:
            %s

            Steps and raw executor outputs:
            %s
            """.formatted(run.getTask(), nullToBlank(run.getClarificationAnswer()), json.toJson(steps));

        return executeReviewerDecision(reviewer, run, prompt, true);
    }

    ReviewerDecision executeReviewerDecision(TeamMember reviewer, TeamRunEntity run,
                                             String prompt, boolean requireFinalReport) {
        String raw = executeTeamInternal(reviewer.agentId(), reviewerSession(run), prompt).getFinalResponse();
        for (int attempt = 0; attempt <= MAX_REVIEWER_FORMAT_REPAIRS; attempt++) {
            try {
                ReviewerDecision decision = json.parseDecision(raw);
                validateReviewerDecision(decision, requireFinalReport);
                return decision;
            } catch (IllegalArgumentException e) {
                if (attempt >= MAX_REVIEWER_FORMAT_REPAIRS) {
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
            Your previous Reviewer response was not valid structured JSON.
            Reformat the same decision as valid JSON only. Do not add markdown, comments, or prose.
            Escape all quotes and newlines inside string values.
            Required schema:
            {
              "action": "CONTINUE | RETRY | ASK_CLARIFICATION",
              "reason": "why",
              "questions": [],
              "retryStepIds": [],
              "revisionInstructions": "",
              "finalReport": ""
            }

            Parse error:
            %s

            Original Reviewer instruction:
            %s

            Invalid response to repair:
            %s
            """.formatted(nullToBlank(parseError), originalPrompt, nullToBlank(invalidResponse));

        return executeTeamInternal(reviewer.agentId(), reviewerSession(run) + "-repair", repairPrompt)
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
        return all.stream()
            .filter(step -> retryStepIds.contains(step.getStepId()))
            .filter(step -> step.getRetryCount() < MAX_STEP_RETRIES)
            .toList();
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
        stepRepository.deleteByRunId(run.getRunId());
        int index = 1;
        for (PlannedStep item : planned) {
            TeamStepEntity step = new TeamStepEntity();
            step.setStepId("step-" + index + "-" + UUID.randomUUID().toString().substring(0, 8));
            step.setRunId(run.getRunId());
            step.setStepIndex(index);
            step.setTitle(item.title());
            step.setDescription(item.description());
            step.setRequiredCapabilitiesJson(json.toJson(item.requiredCapabilities()));
            step.setAcceptanceCriteria(item.acceptanceCriteria());
            step.setStatus(TeamStepStatus.PENDING);
            stepRepository.save(step);
            index++;
        }
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

    private TeamRunEntity loadRun(String teamId, String runId) {
        TeamRunEntity run = runRepository.findById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));
        if (!run.getTeamId().equals(teamId)) {
            throw new IllegalArgumentException("Run does not belong to team: " + runId);
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
            run.getTask(),
            run.getStatus(),
            run.getClarificationQuestion(),
            run.getClarificationAnswer(),
            run.getClarificationStage() == null ? null : run.getClarificationStage().name(),
            run.getPlanReviewJson(),
            run.getResultReviewJson(),
            run.getFinalOutput(),
            run.getMermaidDiagram(),
            run.getPlanRetryCount(),
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
            step.getTitle(),
            step.getDescription(),
            json.stringList(step.getRequiredCapabilitiesJson()),
            step.getAcceptanceCriteria(),
            step.getAssignedAgentId(),
            step.getStatus(),
            step.getRawOutput(),
            step.getRevisionInstructions(),
            step.getReviewStatus(),
            step.getRetryCount(),
            step.getStartedAt(),
            step.getCompletedAt()
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

    private TeamMember member(TeamSnapshot team, TeamRole role) {
        return team.members().stream()
            .filter(item -> item.role() == role)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("Missing team role: " + role));
    }

    private String executorCapabilities(TeamSnapshot team) {
        Map<String, Object> capabilities = new LinkedHashMap<>();
        for (TeamMember member : team.members()) {
            if (member.role() == TeamRole.EXECUTOR) {
                capabilities.put(member.agentId(), member.capabilityTags());
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
}
