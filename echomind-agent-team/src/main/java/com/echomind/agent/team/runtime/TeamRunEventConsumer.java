package com.echomind.agent.team.runtime;

import com.echomind.agent.team.config.TeamRabbitMQConfig;
import com.echomind.agent.team.messaging.TeamRunEvent;
import com.rabbitmq.client.Channel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnClass(TeamStepCommandProducer.class)
@Slf4j
public class TeamRunEventConsumer {

    private static final int MAX_REQUEUE_RETRIES = 5;

    private final TeamDagCoordinator coordinator;
    private final TeamRedisDagStore dagStore;

    public TeamRunEventConsumer(TeamDagCoordinator coordinator, TeamRedisDagStore dagStore) {
        this.coordinator = coordinator;
        this.dagStore = dagStore;
    }

    @RabbitListener(
        queues = "#{@" + TeamRabbitMQConfig.TEAM_RUN_EVENT_SHARD_QUEUES_BEAN + "}",
        containerFactory = TeamRabbitMQConfig.TEAM_RUN_EVENT_LISTENER_FACTORY)
    public void onRunEvent(TeamRunEvent event, Channel channel,
                           @Header(AmqpHeaders.DELIVERY_TAG) long deliveryTag,
                           @Header(value = "x-death", required = false) List<Map<String, Object>> xDeathHeader) {
        String runId = event.runId();
        try {
            if (dagStore.isMessageProcessed(event.messageId())) {
                channel.basicAck(deliveryTag, false);
                return;
            }

            int priorRetries = Math.max(event.retryCount(), countPriorRetries(xDeathHeader));
            if (priorRetries >= MAX_REQUEUE_RETRIES) {
                log.error(
                    "Run 事件重试耗尽，发送到 DLQ: type={} runId={} stepId={} messageId={} retries={}",
                    event.getClass().getSimpleName(),
                    runId,
                    event.stepId(),
                    event.messageId(),
                    priorRetries);
                channel.basicNack(deliveryTag, false, false);
                return;
            }

            TeamRunEvent enriched = (TeamRunEvent) event.withRetryCount(priorRetries);

            log.debug(
                "处理 Run 事件: type={} runId={} stepId={} messageId={} retry={}",
                enriched.getClass().getSimpleName(),
                runId,
                enriched.stepId(),
                enriched.messageId(),
                enriched.retryCount());
            coordinator.handle(runId, enriched);

            dagStore.markMessageProcessed(event.messageId());
            channel.basicAck(deliveryTag, false);

        } catch (DagStateConflictException e) {
            log.warn("DAG 状态冲突，事件 {}: {}", event.messageId(), e.getMessage());
            try {
                channel.basicNack(deliveryTag, false, false);
            } catch (Exception ignored) {
            }

        } catch (Exception e) {
            int nextRetries = Math.max(
                dagStore.incrementMessageRetry(event.messageId()),
                Math.max(event.retryCount(), countPriorRetries(xDeathHeader)) + 1);
            if (nextRetries > MAX_REQUEUE_RETRIES) {
                log.error(
                    "处理 Run 事件失败且重试耗尽，发送到 DLQ: type={} runId={} stepId={} messageId={} retries={} error={}",
                    event.getClass().getSimpleName(),
                    runId,
                    event.stepId(),
                    event.messageId(),
                    nextRetries - 1,
                    e.getMessage(),
                    e);
                try {
                    channel.basicNack(deliveryTag, false, false);
                } catch (Exception ignored) {
                }
                return;
            }
            log.warn(
                "处理 Run 事件失败，将重新入队: type={} runId={} stepId={} messageId={} retry={}/{} error={}",
                event.getClass().getSimpleName(),
                runId,
                event.stepId(),
                event.messageId(),
                nextRetries,
                MAX_REQUEUE_RETRIES,
                e.getMessage(),
                e);
            try {
                channel.basicNack(deliveryTag, false, true);
            } catch (Exception ignored) {
            }
        }
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
