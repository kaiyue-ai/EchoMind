package com.echomind.agent.team.runtime;

import com.echomind.agent.team.config.TeamRabbitMQConfig;
import com.echomind.agent.team.messaging.ExecuteStepCommand;
import com.echomind.agent.team.messaging.StepCompleted;
import com.echomind.agent.team.messaging.StepFailed;
import com.echomind.agent.team.messaging.StepTimeout;
import com.echomind.agent.team.state.TeamStepStatus;
import com.echomind.agent.team.store.TeamStepEntity;
import com.echomind.agent.team.store.TeamStepMapper;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnClass(TeamStepCommandProducer.class)
@Slf4j
public class TeamStepExecutionConsumer {

    private static final int MAX_REQUEUE_RETRIES = 5;

    private final TeamBlackboardService blackboard;
    private final TeamStepCommandProducer producer;
    private final TeamRedisDagStore dagStore;
    private final TeamStepMapper stepMapper;
    private final TeamRuntimeProperties properties;

    public TeamStepExecutionConsumer(TeamBlackboardService blackboard,
                                     TeamStepCommandProducer producer,
                                     TeamRedisDagStore dagStore,
                                     TeamStepMapper stepMapper,
                                     TeamRuntimeProperties properties) {
        this.blackboard = blackboard;
        this.producer = producer;
        this.dagStore = dagStore;
        this.stepMapper = stepMapper;
        this.properties = properties;
    }

    @RabbitListener(
        queues = TeamRabbitMQConfig.STEP_EXECUTE_QUEUE,
        containerFactory = TeamRabbitMQConfig.TEAM_STEP_EXECUTE_LISTENER_FACTORY)
    public void onExecuteStep(ExecuteStepCommand cmd, Channel channel,
                              @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                              @Header(value = "x-death", required = false) List<Map<String, Object>> xDeathHeader) {
        String runId = cmd.runId();
        String stepId = cmd.stepId();
        try {
            String stopping = dagStore.getControlFlag(runId, "stopping");
            if (stopping != null) {
                log.info("Run {} 正在停止 ({}), 跳过步骤 {}", runId, stopping, stepId);
                dagStore.markMessageProcessed(cmd.messageId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            if (dagStore.isMessageProcessed(cmd.messageId())) {
                channel.basicAck(deliveryTag, false);
                return;
            }

            TeamStepEntity step = stepMapper.selectById(stepId);
            if (step == null || isTerminal(step.getStatus())) {
                log.info("步骤 {} 已是终态 ({}), 跳过", stepId, step != null ? step.getStatus() : "null");
                dagStore.markMessageProcessed(cmd.messageId());
                channel.basicAck(deliveryTag, false);
                return;
            }
            if (!isExecutable(step.getStatus())) {
                log.warn("步骤 {} 在 MySQL 状态 {} 下不可执行，发布失败事件", stepId, step.getStatus());
                publishStepFailed(runId, stepId,
                    "步骤在 MySQL 状态 " + step.getStatus() + " 下不可执行", cmd, channel, deliveryTag);
                return;
            }

            // tryClaimSlot already atomically sets step status to RUNNING in Redis
            blackboard.markStepExecutionAccepted(runId, stepId);

            long startedAt = System.currentTimeMillis();
            int stepTimeoutSecs = properties.getStepTimeoutSeconds();
            if (stepTimeoutSecs > 0) {
                executeWithTimeout(runId, stepId, stepTimeoutSecs, cmd, channel, deliveryTag);
            } else {
                blackboard.executeStepPublic(runId, stepId);
                long elapsed = System.currentTimeMillis() - startedAt;
                publishOutcome(runId, stepId, cmd, channel, deliveryTag, elapsed);
            }

        } catch (StepExecutionException e) {
            log.warn("步骤 {} 执行失败: {}", stepId, e.getMessage());
            publishStepFailed(runId, stepId, e.getMessage(), cmd, channel, deliveryTag);

        } catch (Exception e) {
            if (isInfrastructureException(e)) {
                int priorRetries = countPriorRetries(xDeathHeader);
                if (priorRetries >= MAX_REQUEUE_RETRIES) {
                    log.error("步骤 {} 重新入队重试次数耗尽 ({})，发送到 DLQ", stepId, priorRetries);
                    try {
                        channel.basicNack(deliveryTag, false, false);
                    } catch (Exception ignored) {
                    }
                } else {
                    log.warn("步骤 {} 基础设施失败，重新入队 ({}/{})", stepId, priorRetries + 1, MAX_REQUEUE_RETRIES);
                    try {
                        channel.basicNack(deliveryTag, false, true);
                    } catch (Exception ignored) {
                    }
                }
            } else {
                log.error("步骤 {} 执行遇到未预期异常，发送到 DLQ", stepId, e);
                try {
                    channel.basicNack(deliveryTag, false, false);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private boolean isTerminal(Object status) {
        if (status == null) return true;
        String s = status instanceof String ? (String) status : status.toString();
        return "COMPLETED".equals(s) || "FAILED".equals(s) || "SUPERSEDED".equals(s);
    }

    private boolean isInfrastructureException(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        String cls = e.getClass().getName().toLowerCase();
        return cls.contains("datasource") || cls.contains("redis")
            || cls.contains("sql") || cls.contains("jdbc")
            || msg.contains("connection") || msg.contains("timeout");
    }

    private int countPriorRetries(List<Map<String, Object>> xDeathHeader) {
        if (xDeathHeader == null) return 0;
        return xDeathHeader.stream()
            .filter(death -> "rejected".equals(death.get("reason")))
            .mapToInt(death -> {
                Object count = death.get("count");
                return count instanceof Number ? ((Number) count).intValue() : 1;
            })
            .sum();
    }

    private void executeWithTimeout(String runId, String stepId, int timeoutSecs,
                                     ExecuteStepCommand cmd, Channel channel, long deliveryTag) {
        long startedAt = System.currentTimeMillis();
        try {
            CompletableFuture
                .runAsync(() -> blackboard.executeStepPublic(runId, stepId))
                .orTimeout(timeoutSecs, TimeUnit.SECONDS)
                .join();

            long elapsed = System.currentTimeMillis() - startedAt;
            publishOutcome(runId, stepId, cmd, channel, deliveryTag, elapsed);

        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                long elapsed = System.currentTimeMillis() - startedAt;
                log.warn("步骤 {} 超时，耗时 {}ms (限制 {}s)", stepId, elapsed, timeoutSecs);

                StepTimeout timeoutEvent = new StepTimeout(
                    UUID.randomUUID().toString(), runId, stepId, Instant.now(), 0, elapsed
                );
                producer.publishRunEvent(runId, timeoutEvent);
                dagStore.markMessageProcessed(cmd.messageId());

                try {
                    channel.basicAck(deliveryTag, false);
                } catch (Exception ignored) {
                }
            } else {
                Throwable cause = e.getCause();
                if (cause instanceof StepExecutionException se) {
                    throw se;
                }
                if (cause instanceof RuntimeException re) {
                    throw re;
                }
                throw new StepExecutionException("步骤执行遇到未预期异常: " + cause.getMessage(), cause);
            }
        }
    }

    private void publishOutcome(String runId, String stepId, ExecuteStepCommand cmd,
                                Channel channel, long deliveryTag, long elapsedMs) {
        StepExecutionOutcome outcome = blackboard.stepExecutionOutcome(stepId);

        if (outcome.completed()) {
            publishStepCompleted(runId, stepId, outcome.output(), cmd, channel, deliveryTag, elapsedMs);
            return;
        }

        if (outcome.retrying()) {
            dagStore.releaseSlotForRetry(runId, stepId, outcome.retryCount());
            if (dagStore.markStepReady(runId, stepId)) {
                producer.publishExecuteStep(runId, stepId);
            }
            ackProcessed(cmd, channel, deliveryTag);
            log.info("步骤 {} 执行后需要重试，已重新入队，重试次数={}", stepId, outcome.retryCount());
            return;
        }

        if (outcome.runTerminal()) {
            ackProcessed(cmd, channel, deliveryTag);
            log.info("步骤 {} 执行结束，Run 状态为终态 {}，不发布 DAG 完成事件", stepId, outcome.runStatus());
            return;
        }

        String message = outcome.missing()
            ? "步骤在执行过程中消失"
            : "步骤执行未完成，状态=" + outcome.stepStatus();
        publishStepFailed(runId, stepId, message, cmd, channel, deliveryTag);
    }

    private void publishStepCompleted(String runId, String stepId, String output, ExecuteStepCommand cmd,
                                      Channel channel, long deliveryTag, long elapsedMs) {
        StepCompleted completed = new StepCompleted(
            UUID.randomUUID().toString(), runId, stepId, Instant.now(), 0,
            output != null ? output : ""
        );
        producer.publishRunEvent(runId, completed);
        ackProcessed(cmd, channel, deliveryTag);
        log.debug("步骤 {} 执行成功，耗时 {}ms", stepId, elapsedMs);
    }

    private void publishStepFailed(String runId, String stepId, String message, ExecuteStepCommand cmd,
                                   Channel channel, long deliveryTag) {
        StepFailed failed = new StepFailed(
            UUID.randomUUID().toString(), runId, stepId, Instant.now(), 0,
            message == null || message.isBlank() ? "步骤执行失败" : message
        );
        producer.publishRunEvent(runId, failed);
        ackProcessed(cmd, channel, deliveryTag);
    }

    private void ackProcessed(ExecuteStepCommand cmd, Channel channel, long deliveryTag) {
        dagStore.markMessageProcessed(cmd.messageId());
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception ignored) {
        }
    }

    private boolean isExecutable(Object status) {
        String s = status instanceof String ? (String) status : String.valueOf(status);
        return TeamStepStatus.READY.name().equals(s)
            || TeamStepStatus.RETRYING.name().equals(s)
            || TeamStepStatus.RUNNING.name().equals(s);
    }

    public static class StepExecutionException extends RuntimeException {
        public StepExecutionException(String message) {
            super(message);
        }

        public StepExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
