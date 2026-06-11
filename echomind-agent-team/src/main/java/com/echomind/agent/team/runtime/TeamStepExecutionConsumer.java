package com.echomind.agent.team.runtime;

import com.echomind.agent.team.config.TeamRabbitMQConfig;
import com.echomind.agent.team.messaging.ExecuteStepCommand;
import com.echomind.agent.team.messaging.StepCompleted;
import com.echomind.agent.team.messaging.StepFailed;
import com.echomind.agent.team.messaging.StepTimeout;
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
            // 1. Control flag check
            String stopping = dagStore.getControlFlag(runId, "stopping");
            if (stopping != null) {
                log.info("Run {} is stopping ({}), skip step {}", runId, stopping, stepId);
                dagStore.markMessageProcessed(cmd.messageId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 2. Deduplication
            if (dagStore.isMessageProcessed(cmd.messageId())) {
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 3. Idempotency via MySQL status
            TeamStepEntity step = stepMapper.selectById(stepId);
            if (step == null || isTerminal(step.getStatus())) {
                log.info("Step {} already terminal ({}), skip", stepId, step != null ? step.getStatus() : "null");
                dagStore.markMessageProcessed(cmd.messageId());
                channel.basicAck(deliveryTag, false);
                return;
            }

            // 4. Mark RUNNING in Redis
            dagStore.setStepRunning(runId, stepId);

            // 5. Execute step with timeout protection
            long startedAt = System.currentTimeMillis();
            int stepTimeoutSecs = properties.getStepTimeoutSeconds();
            if (stepTimeoutSecs > 0) {
                executeWithTimeout(runId, stepId, stepTimeoutSecs, cmd, channel, deliveryTag);
            } else {
                blackboard.executeStepPublic(runId, stepId);
                long elapsed = System.currentTimeMillis() - startedAt;
                publishStepCompleted(runId, stepId, cmd, channel, deliveryTag, elapsed);
            }

        } catch (StepExecutionException e) {
            // Business-level step failure → publish StepFailed, ack
            log.warn("Step {} execution failed: {}", stepId, e.getMessage());
            StepFailed failed = new StepFailed(
                UUID.randomUUID().toString(), runId, stepId, Instant.now(), 0,
                e.getMessage()
            );
            producer.publishRunEvent(runId, failed);
            dagStore.markMessageProcessed(cmd.messageId());
            try {
                channel.basicAck(deliveryTag, false);
            } catch (Exception ignored) {
            }

        } catch (Exception e) {
            // Check if it's a data source / Redis connectivity error (requeue)
            if (isInfrastructureException(e)) {
                // Count prior requeues
                int priorRetries = countPriorRetries(xDeathHeader);
                if (priorRetries >= MAX_REQUEUE_RETRIES) {
                    log.error("Step {} requeue retries exhausted ({}), sending to DLQ", stepId, priorRetries);
                    try {
                        channel.basicNack(deliveryTag, false, false);
                    } catch (Exception ignored) {
                    }
                } else {
                    log.warn("Infra failure for step {}, requeue ({}/{})", stepId, priorRetries + 1, MAX_REQUEUE_RETRIES);
                    try {
                        channel.basicNack(deliveryTag, false, true);
                    } catch (Exception ignored) {
                    }
                }
            } else {
                // Unexpected exception → DLQ
                log.error("Unexpected error executing step {}, sending to DLQ", stepId, e);
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
            publishStepCompleted(runId, stepId, cmd, channel, deliveryTag, elapsed);
        } catch (CompletionException e) {
            if (e.getCause() instanceof TimeoutException) {
                long elapsed = System.currentTimeMillis() - startedAt;
                log.warn("Step {} timed out after {}ms (limit {}s)", stepId, elapsed, timeoutSecs);
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
                throw new RuntimeException("Unexpected error during step execution", cause);
            }
        }
    }

    private void publishStepCompleted(String runId, String stepId, ExecuteStepCommand cmd,
                                       Channel channel, long deliveryTag, long elapsedMs) {
        String output = stepMapper.selectById(stepId).getRawOutput();
        StepCompleted completed = new StepCompleted(
            UUID.randomUUID().toString(), runId, stepId, Instant.now(), 0,
            output != null ? output : ""
        );
        producer.publishRunEvent(runId, completed);
        dagStore.markMessageProcessed(cmd.messageId());
        try {
            channel.basicAck(deliveryTag, false);
        } catch (Exception ignored) {
        }
        log.debug("Step {} completed successfully in {}ms", stepId, elapsedMs);
    }

    /**
     * Thrown when step execution fails at the business level (LLM error, tool error).
     */
    public static class StepExecutionException extends RuntimeException {
        public StepExecutionException(String message) {
            super(message);
        }

        public StepExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
