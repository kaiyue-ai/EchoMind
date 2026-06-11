package com.echomind.console.deadletter;

import java.time.Instant;
import java.util.List;

public final class RabbitDeadLetterDtos {

    private RabbitDeadLetterDtos() {
    }

    public record DeadLetterListResponse(List<DeadLetterView> items) {
    }

    public record DeadLetterReplayResponse(DeadLetterView item) {
    }

    public record DeadLetterView(
        Long id,
        String dlqName,
        String messageType,
        String businessKey,
        String traceId,
        String payloadJson,
        String errorHeadersJson,
        String status,
        int replayCount,
        String lastReplayError,
        Instant archivedAt,
        Instant replayedAt
    ) {
    }

    public static DeadLetterView view(RabbitDeadLetterEntity entity) {
        return new DeadLetterView(
            entity.getId(),
            entity.getDlqName(),
            entity.getMessageType(),
            entity.getBusinessKey(),
            entity.getTraceId(),
            entity.getPayloadJson(),
            entity.getErrorHeadersJson(),
            entity.getStatus(),
            entity.getReplayCount(),
            entity.getLastReplayError(),
            entity.getArchivedAt(),
            entity.getReplayedAt()
        );
    }
}
