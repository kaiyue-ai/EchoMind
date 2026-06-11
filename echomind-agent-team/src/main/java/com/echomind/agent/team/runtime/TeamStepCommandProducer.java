package com.echomind.agent.team.runtime;

import com.echomind.agent.messaging.RabbitReliableMessaging;
import com.echomind.agent.team.config.TeamRabbitMQConfig;
import com.echomind.agent.team.messaging.ExecuteStepCommand;
import com.echomind.agent.team.messaging.TeamMessage;
import com.echomind.agent.team.messaging.TeamRunEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.UUID;

@Slf4j
public class TeamStepCommandProducer {

    private final RabbitTemplate rabbitTemplate;
    private final int shardCount;

    public TeamStepCommandProducer(RabbitTemplate teamRabbitTemplate,
                                   int shardCount) {
        this.rabbitTemplate = teamRabbitTemplate;
        this.shardCount = shardCount;
    }

    public void publishExecuteStep(String runId, String stepId) {
        ExecuteStepCommand cmd = new ExecuteStepCommand(
            newMessageId(), runId, stepId, Instant.now(), 0
        );
        doPublish(TeamRabbitMQConfig.STEP_EXECUTE_ROUTING_KEY, cmd, "team-step", runId);
    }

    public void publishRunEvent(String runId, TeamRunEvent event) {
        String routingKey = TeamRabbitMQConfig.routingKeyForRun(runId, shardCount);
        doPublish(routingKey, event, "team-event", runId);
    }

    private void doPublish(String routingKey, TeamMessage message, String type, String businessId) {
        try {
            rabbitTemplate.convertAndSend(
                TeamRabbitMQConfig.TEAM_EXCHANGE,
                routingKey,
                message,
                RabbitReliableMessaging.persistentMessage(),
                RabbitReliableMessaging.correlation(type, businessId)
            );
            log.debug("Published {} to {} routingKey={} messageId={} runId={}",
                type, TeamRabbitMQConfig.TEAM_EXCHANGE, routingKey,
                message.messageId(), message.runId());
        } catch (Exception e) {
            log.error("Failed to publish {} to routingKey={}: {}", type, routingKey, e.getMessage(), e);
            throw new TeamMessagePublishException("Failed to publish " + type, e);
        }
    }

    private static String newMessageId() {
        return UUID.randomUUID().toString();
    }

    public static class TeamMessagePublishException extends RuntimeException {
        public TeamMessagePublishException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
