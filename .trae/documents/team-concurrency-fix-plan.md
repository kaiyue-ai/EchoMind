# Team 并发问题修复计划

## 已完成的修复（本轮对话中）

1. ✅ 删除无效 version 字段（TeamRunEntity、TeamRunMapper、迁移脚本）
2. ✅ 合并 SIMPLE/COMPLEX 路径（删除 executeSimpleRun，统一走 DAG）
3. ✅ 删除 SIMPLE_DRAFT_STARTED/SIMPLE_DRAFT_COMPLETED 枚举和前端映射
4. ✅ 澄清逻辑修复：planRetryCount/resultReplanCount/partialReplanCount 归零，清空 clarificationQuestion/stage
5. ✅ RESULT_REVIEW 澄清后不再重新执行 Step，改为带澄清答案重新合并
6. ✅ 删除无用的 prepareStepsForClarificationResume 方法
7. ✅ FAILED Step 提前检查（DAG 循环中 readySteps 不为空时也检查 FAILED）
8. ✅ stoppingRunIds 防止 resumeRun 和原线程并发执行
9. ✅ isRunDeleted 检查（recordEvent/markStepFailed/markStepTimedOut/completeStep/executeStepSafely/handleStepFutureFailure/DAG循环/executeRun）
10. ✅ SubReviewer 澄清时 Step 状态改为 PENDING + 写入 revisionInstructions

## 待修复的问题

### 问题 1：两个并行 Step 同时要求澄清，clarificationQuestion 被覆盖 + 前端竞态

**场景**：
- Step A 和 Step B 并行执行，都是高风险 Step
- Step A 的 SubReviewer 先要求澄清 → pauseForClarification → Run = NEEDS_CLARIFICATION
- 前端看到 NEEDS_CLARIFICATION → 用户立即回答 → resumeRun
- Step B 的 SubReviewer 还没审查完，或者审查完但问题被覆盖

**根本原因**：
`pauseForClarification` 在子线程（CompletableFuture.runAsync）里调用，导致 Run 在所有并行 Step 完成之前就变成 NEEDS_CLARIFICATION，前端过早看到澄清请求。

**修复方案**：
不在子线程里调用 `pauseForClarification`。改为：
1. 子线程里只标记 Step 需要澄清（设 PENDING + revisionInstructions），不调 pauseForClarification
2. 用 ConcurrentHashMap 收集澄清问题（runId → question）
3. allOf.join() 后，主线程统一检查并调用一次 pauseForClarification，合并所有问题

这样前端只会在所有并行 Step 都审查完后才看到 NEEDS_CLARIFICATION，不会出现第二个问题还没到就提交答案的情况。

## 实施步骤

### Step 1：添加 pendingClarifications 字段

```java
private final Map<String, String> pendingClarifications = new ConcurrentHashMap<>();
```

### Step 2：修改 reviewHighRiskStep 的 ASK_CLARIFICATION 分支

不再调用 `pauseForClarification`，改为收集澄清问题：

```java
if (decision.action() == ReviewerAction.ASK_CLARIFICATION) {
    step.setStatus(TeamStepStatus.PENDING);
    step.setRevisionInstructions("SubReviewer requested clarification: " + decision.reason());
    stepMapper.upsertById(step);
    String question = decision.questions().isEmpty()
        ? decision.reason()
        : String.join("\n", decision.questions());
    pendingClarifications.merge(run.getRunId(), question, (existing, newQ) -> existing + "\n\n" + newQ);
    return;
}
```

### Step 3：修改 DAG 循环 allOf.join() 后的逻辑

在 `stoppingRunIds` 检查之后、DB 状态检查之前，处理收集到的澄清问题：

```java
// 3.8 检查是否应该停止
if (stoppingRunIds.remove(run.getRunId())) {
    return;
}

// 3.9 处理子线程收集的澄清请求
String clarificationQuestion = pendingClarifications.remove(run.getRunId());
if (clarificationQuestion != null) {
    run.setClarificationQuestion(clarificationQuestion);
    run.setClarificationStage(TeamClarificationStage.RESULT_REVIEW);
    pauseForClarification(run, new ReviewerDecision(ReviewerAction.ASK_CLARIFICATION, clarificationQuestion, List.of(clarificationQuestion.split("\n"))), TeamClarificationStage.RESULT_REVIEW);
    return;
}

// 3.10 检查最新状态
TeamRunEntity latest = runMapper.selectOptionalById(run.getRunId()).orElse(run);
...
```

### Step 4：修改 pauseForClarification

当前 `pauseForClarification` 从 `decision` 里构建 question，但主线程调用时 question 已经在 `run` 上了。需要调整 `pauseForClarification` 使其支持直接使用 `run` 上已有的 question。

或者更简单：主线程直接设置 Run 状态，不调 `pauseForClarification`：

```java
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
```

### Step 5：同样处理 planAndReview 和 reviewResults 中的 pauseForClarification

这两个方法是在主线程（executeRun 的 taskExecutor 线程）里调用的，不存在并行问题，保持原样。

### Step 6：编译测试

```powershell
mvn.cmd -q -DskipTests compile
mvn.cmd -q test
```

### Step 7：Git commit & push
