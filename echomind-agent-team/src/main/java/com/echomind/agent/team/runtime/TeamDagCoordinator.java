package com.echomind.agent.team.runtime;

import com.echomind.agent.team.messaging.RunCancelled;
import com.echomind.agent.team.messaging.RunStarted;
import com.echomind.agent.team.messaging.StepCompleted;
import com.echomind.agent.team.messaging.StepFailed;
import com.echomind.agent.team.messaging.StepTimeout;
import com.echomind.agent.team.messaging.TeamControlAction;
import com.echomind.agent.team.messaging.TeamRunEvent;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * DAG 协调器。
 *
 * <p>负责按顺序处理 Run 事件（通过分片队列保证顺序），协调依赖解析、槽位管理和步骤调度。
 *
 * <p>核心职责： - 接收并分发 Run 级事件到对应的处理器 - 管理 DAG 状态机的推进 - 处理步骤完成后的级联依赖检查 - 调度就绪步骤执行
 *
 * <p>运行在 Run Events 消费者线程中，与 {@link TeamRunEventConsumer} 协作。
 */
@Slf4j
public class TeamDagCoordinator {

  /** Redis DAG 存储 - 管理运行时状态和槽位 */
  private final TeamRedisDagStore dagStore;

  /** 命令生产者 - 发布步骤执行命令到 RabbitMQ */
  private final TeamStepCommandProducer producer;

  /** 黑板服务 - 处理 MySQL 持久化和业务逻辑 */
  private final TeamBlackboardService blackboard;

  /** 构造函数 - 依赖注入 */
  public TeamDagCoordinator(
      TeamRedisDagStore dagStore,
      TeamStepCommandProducer producer,
      TeamBlackboardService blackboard) {
    this.dagStore = dagStore;
    this.producer = producer;
    this.blackboard = blackboard;
  }

  /**
   * 将 Run 事件分发到对应的处理器。
   *
   * @param runId 运行 ID
   * @param event Run 事件（RunStarted、StepCompleted、StepFailed 等）
   */
  public void handle(String runId, TeamRunEvent event) {
    log.debug(
        "协调器收到事件 type={} runId={} messageId={}",
        event.getClass().getSimpleName(),
        runId,
        event.messageId());

    // 根据事件类型分发到对应的处理方法
    if (event instanceof RunStarted rs) {
      onRunStarted(rs);
    } else if (event instanceof StepCompleted sc) {
      onStepCompleted(sc);
    } else if (event instanceof StepFailed sf) {
      onStepFailed(sf);
    } else if (event instanceof StepTimeout st) {
      onStepTimeout(st);
    } else if (event instanceof RunCancelled rc) {
      onRunCancelled(rc);
    }
  }

  // ==================== 事件处理器 ====================

  /**
   * 处理 Run 启动事件。
   *
   * <p>执行流程：派发耗时的规划命令。Run event 消费者只保留轻量 DAG 推进，避免 Planner LLM 调用阻塞
   * 同 shard 后续事件。
   *
   * @param event RunStarted 事件
   */
  void onRunStarted(RunStarted event) {
    String runId = event.runId();
    log.info("DAG 协调器收到 RunStarted，派发规划命令 runId={}", runId);
    producer.publishControl(runId, TeamControlAction.PLAN_AND_REVIEW);
  }

  /**
   * 处理步骤完成事件。
   *
   * <p>执行流程： 1. 调用 Lua 脚本完成步骤并级联检查依赖 2. 更新 MySQL 中就绪步骤的状态 3. 调度新就绪的步骤 4. 检查 DAG 是否全部完成
   *
   * @param event StepCompleted 事件
   */
  void onStepCompleted(StepCompleted event) {
    String runId = event.runId();
    String stepId = event.stepId();

    // 1. Lua 脚本：完成步骤 + 级联检查依赖，返回新就绪的步骤列表
    List<String> newlyReady = dagStore.completeStepAndCascade(runId, stepId, event.output());
    log.debug("步骤 {} 完成，{} 个新就绪步骤: {}", stepId, newlyReady.size(), newlyReady);

    // 2. 更新 MySQL 中就绪步骤的状态
    blackboard.markStepsReadyFromCoordinator(runId, newlyReady);

    // 3. 调度新就绪的步骤
    dispatchReadySteps(runId);

    // 4. 检查 DAG 是否全部完成
    if (dagStore.isDagComplete(runId)) {
      log.info("DAG 完成，派发汇总命令 runId={}", runId);
      producer.publishControl(runId, TeamControlAction.DAG_COMPLETE);
    }
  }

  /**
   * 处理步骤失败事件。
   *
   * <p>执行流程： - 如果重试次数未耗尽：标记步骤为 RETRYING，重新入队 - 如果重试次数耗尽：释放槽位，标记 Run 失败
   *
   * @param event StepFailed 事件
   */
  void onStepFailed(StepFailed event) {
    String runId = event.runId();
    String stepId = event.stepId();

    // 获取当前重试次数和最大重试次数
    int retryCount = dagStore.getStepRetryCount(runId, stepId);
    int maxRetries = blackboard.getMaxStepRetries();

    if (retryCount < maxRetries) {
      // 重试：设置状态为 RETRYING，重新入队为 READY
      dagStore.releaseSlotForRetry(runId, stepId, retryCount + 1);
      dagStore.markStepReady(runId, stepId);
      blackboard.markStepRetryingFromCoordinator(
          runId, stepId, retryCount + 1, event.errorMessage());
      log.info("步骤 {} 失败，重试 ({}/{})", stepId, retryCount + 1, maxRetries);
      dispatchReadySteps(runId);
    } else {
      // 重试次数耗尽：释放槽位，标记 Run 失败
      dagStore.releaseSlot(runId, stepId);
      log.warn("步骤 {} 失败 {} 次后放弃，Run {} 失败", stepId, retryCount, runId);
      blackboard.failRunFromCoordinator(
          runId, "步骤 " + stepId + " 失败 " + retryCount + " 次: " + event.errorMessage());
    }
  }

  /**
   * 处理步骤超时事件。
   *
   * <p>执行流程与步骤失败类似： - 如果重试次数未耗尽：标记步骤为 RETRYING，重新入队 - 如果重试次数耗尽：释放槽位，标记 Run 失败
   *
   * @param event StepTimeout 事件
   */
  void onStepTimeout(StepTimeout event) {
    String runId = event.runId();
    String stepId = event.stepId();

    int retryCount = dagStore.getStepRetryCount(runId, stepId);
    int maxRetries = blackboard.getMaxStepRetries();

    if (retryCount < maxRetries) {
      dagStore.releaseSlotForRetry(runId, stepId, retryCount + 1);
      dagStore.markStepReady(runId, stepId);
      blackboard.markStepRetryingFromCoordinator(
          runId, stepId, retryCount + 1, "步骤超时，耗时 " + event.durationMs() + "ms");
      log.info("步骤 {} 超时 {}ms，重试 ({}/{})", stepId, event.durationMs(), retryCount + 1, maxRetries);
      dispatchReadySteps(runId);
    } else {
      dagStore.releaseSlot(runId, stepId);
      log.warn("步骤 {} 超时 {} 次后放弃，Run {} 失败", stepId, retryCount, runId);
      blackboard.failRunFromCoordinator(runId, "步骤 " + stepId + " 超时 " + retryCount + " 次");
    }
  }

  /**
   * 处理 Run 取消事件。
   *
   * <p>设置停止标志，标记 DAG 状态为失败，并通知黑板服务。
   *
   * @param event RunCancelled 事件
   */
  void onRunCancelled(RunCancelled event) {
    String runId = event.runId();
    dagStore.setControlFlag(runId, "stopping", "CANCELLED");
    dagStore.setDagStatus(runId, "FAILED");
    log.info("Run {} 已取消", runId);
    blackboard.failRunFromCoordinator(runId, "Run 已取消");
  }

  // ==================== 步骤调度 ====================

  /**
   * 调度就绪步骤。
   *
   * <p>执行流程： 1. 检查停止标志（暂停/取消） 2. 循环获取槽位并派发步骤执行命令
   *
   * @param runId 运行 ID
   */
  void dispatchReadySteps(String runId) {
    // 检查是否正在停止
    String stopping = dagStore.getControlFlag(runId, "stopping");
    if (stopping != null) {
      log.debug("Run {} 正在停止 ({}), 不调度新步骤", runId, stopping);
      return;
    }

    // 循环获取槽位并派发
    while (dagStore.hasPendingReady(runId)) {
      // Lua 原子操作：获取并发槽位
      String claimed = dagStore.tryClaimSlot(runId);
      if (claimed == null) {
        // 没有可用槽位（达到最大并发数）
        break;
      }
      log.debug("调度步骤 {} 执行，Run {}", claimed, runId);
      // 发布执行命令到 RabbitMQ
      producer.publishExecuteStep(runId, claimed);
    }
  }

  void failRun(String runId, String message) {
    blackboard.failRunFromCoordinator(runId, message);
  }

  void startRunPlan(String runId) {
    blackboard.planAndReviewForCoordinator(runId);
    dispatchReadySteps(runId);
  }

  void completeDag(String runId) {
    blackboard.onDagCompleteInCoordinator(runId);
  }
}
