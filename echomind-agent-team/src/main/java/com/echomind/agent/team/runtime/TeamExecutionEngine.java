package com.echomind.agent.team.runtime;

import com.echomind.agent.team.messaging.RunStarted;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Run-level execution entry point. Publishes a {@link RunStarted} event to the
 * Run Events queue. The DAG Coordinator picks it up from there.
 */
@Slf4j
public class TeamExecutionEngine {

    private final TeamStepCommandProducer producer;

    public TeamExecutionEngine(TeamStepCommandProducer producer) {
        this.producer = producer;
    }

    public void scheduleRun(String runId, Consumer<String> executeCallback,
                            BiConsumer<String, Throwable> failCallback) {
        if (producer == null) {
            log.error("Cannot schedule run {} — TeamStepCommandProducer not configured", runId);
            failCallback.accept(runId, new IllegalStateException("TeamStepCommandProducer not available"));
            return;
        }
        try {
            RunStarted event = new RunStarted(
                UUID.randomUUID().toString(), runId, null, Instant.now(), 0, null
            );
            producer.publishRunEvent(runId, event);
            log.info("Published RunStarted for run {} via RabbitMQ", runId);
        } catch (Exception e) {
            log.error("Failed to schedule run {} via RabbitMQ", runId, e);
            failCallback.accept(runId, e);
        }
    }
}
