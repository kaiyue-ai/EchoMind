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
            // Deduplication
            if (dagStore.isMessageProcessed(event.messageId())) {
                channel.basicAck(deliveryTag, false);
                return;
            }

            // Count prior requeues from x-death header
            int priorRetries = countPriorRetries(xDeathHeader);
            if (priorRetries >= MAX_REQUEUE_RETRIES) {
                log.error("Run event {} retries exhausted ({}), sending to DLQ",
                    event.messageId(), priorRetries);
                channel.basicNack(deliveryTag, false, false);
                return;
            }
            TeamRunEvent enriched = (TeamRunEvent) event.withRetryCount(priorRetries);

            // Process
            coordinator.handle(runId, enriched);

            // Mark processed and ack
            dagStore.markMessageProcessed(event.messageId());
            channel.basicAck(deliveryTag, false);

        } catch (DagStateConflictException e) {
            log.warn("DAG state conflict for event {}: {}", event.messageId(), e.getMessage());
            try {
                channel.basicNack(deliveryTag, false, false); // → DLQ
            } catch (Exception ignored) {
                // channel error, message will eventually be redelivered
            }

        } catch (Exception e) {
            log.error("Failed to process run event {}, will requeue: {}",
                event.messageId(), e.getMessage(), e);
            try {
                channel.basicNack(deliveryTag, false, true); // requeue
            } catch (Exception ignored) {
                // channel error
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
