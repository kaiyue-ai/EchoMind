package com.echomind.agent.team.runtime;

import com.echomind.agent.team.config.TeamRabbitMQConfig;
import com.echomind.agent.team.messaging.TeamControlAction;
import com.echomind.agent.team.messaging.TeamControlCommand;
import com.echomind.common.exception.ModelInvocationRejectedException;
import com.rabbitmq.client.Channel;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnClass(TeamStepCommandProducer.class)
@Slf4j
public class TeamControlConsumer {

    private static final int MAX_REQUEUE_RETRIES = 5;
    private static final int RUN_LOCK_STRIPES = 256;

    private final TeamDagCoordinator coordinator;
    private final TeamRedisDagStore dagStore;
    private final ReentrantLock[] runLocks = new ReentrantLock[RUN_LOCK_STRIPES];

    public TeamControlConsumer(TeamDagCoordinator coordinator, TeamRedisDagStore dagStore) {
        this.coordinator = coordinator;
        this.dagStore = dagStore;
        for (int i = 0; i < runLocks.length; i++) {
            runLocks[i] = new ReentrantLock(true);
        }
    }

    @RabbitListener(
        queues = TeamRabbitMQConfig.CONTROL_QUEUE,
        containerFactory = TeamRabbitMQConfig.TEAM_CONTROL_LISTENER_FACTORY)
    public void onControl(TeamControlCommand cmd,
                          Channel channel,
                          @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                          @Header(value = "x-death", required = false) List<Map<String, Object>> xDeathHeader) {
        String runId = cmd.runId();
        ReentrantLock runLock = lockForRun(runId);
        runLock.lock();
        try {
            if (dagStore.isMessageProcessed(cmd.messageId())) {
                channel.basicAck(deliveryTag, false);
                return;
            }

            int priorRetries = Math.max(cmd.retryCount(), countPriorRetries(xDeathHeader));
            if (priorRetries >= MAX_REQUEUE_RETRIES) {
                log.error(
                    "Team control 命令重试耗尽，发送到 DLQ: action={} runId={} messageId={} retries={}",
                    cmd.action(), runId, cmd.messageId(), priorRetries);
                coordinator.failRun(runId, "Team control retry exhausted: " + cmd.action());
                channel.basicNack(deliveryTag, false, false);
                return;
            }

            log.info(
                "处理 Team control 命令: action={} runId={} messageId={} retry={}",
                cmd.action(), runId, cmd.messageId(), priorRetries);
            handle(cmd.action(), runId);

            dagStore.markMessageProcessed(cmd.messageId());
            channel.basicAck(deliveryTag, false);
        } catch (Exception e) {
            if (isDeterministicFailure(e)) {
                log.error(
                    "Team control 命令发生确定性业务失败，直接标记 Run 失败: action={} runId={} messageId={} error={}",
                    cmd.action(), runId, cmd.messageId(), e.getMessage(), e);
                coordinator.failRun(runId, "Team control failed: " + e.getMessage());
                try {
                    channel.basicAck(deliveryTag, false);
                } catch (Exception ignored) {
                }
                return;
            }

            int nextRetries = Math.max(
                dagStore.incrementMessageRetry(cmd.messageId()),
                Math.max(cmd.retryCount(), countPriorRetries(xDeathHeader)) + 1);
            if (nextRetries > MAX_REQUEUE_RETRIES) {
                log.error(
                    "Team control 命令失败且重试耗尽，发送到 DLQ: action={} runId={} messageId={} retries={} error={}",
                    cmd.action(), runId, cmd.messageId(), nextRetries - 1, e.getMessage(), e);
                coordinator.failRun(runId, "Team control retry exhausted: " + e.getMessage());
                try {
                    channel.basicNack(deliveryTag, false, false);
                } catch (Exception ignored) {
                }
                return;
            }

            log.warn(
                "Team control 命令失败，将重新入队: action={} runId={} messageId={} retry={}/{} error={}",
                cmd.action(), runId, cmd.messageId(), nextRetries, MAX_REQUEUE_RETRIES, e.getMessage(), e);
            try {
                channel.basicNack(deliveryTag, false, true);
            } catch (Exception ignored) {
            }
        } finally {
            runLock.unlock();
        }
    }

    private ReentrantLock lockForRun(String runId) {
        int index = Math.floorMod(runId.hashCode(), runLocks.length);
        return runLocks[index];
    }

    private void handle(TeamControlAction action, String runId) {
        if (action == TeamControlAction.PLAN_AND_REVIEW) {
            coordinator.startRunPlan(runId);
            return;
        }
        if (action == TeamControlAction.DAG_COMPLETE) {
            coordinator.completeDag(runId);
            return;
        }
        throw new IllegalArgumentException("Unknown Team control action: " + action);
    }

    private boolean isDeterministicFailure(Exception e) {
        return e instanceof TeamUsageQuotaExceededException
            || e instanceof ModelInvocationRejectedException;
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
}
