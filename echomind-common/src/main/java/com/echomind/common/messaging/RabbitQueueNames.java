package com.echomind.common.messaging;

/** Shared RabbitMQ exchange and queue names. */
public final class RabbitQueueNames {

    public static final String DEAD_LETTER_EXCHANGE = "echomind.dlx";
    public static final String CHAT_REQUESTS_DLQ = "echomind.chat.requests.dlq";
    public static final String CHAT_MEMORY_PERSIST_DLQ = "echomind.chat-memory.persist.requests.dlq";
    public static final String USER_MEMORY_DLQ = "echomind.user-memory.requests.dlq";
    public static final String TEAM_CONTROL_COMMANDS = "echomind.team.control.commands";
    public static final String TEAM_STEP_COMMANDS = "echomind.team.step.commands";
    public static final String TEAM_RUN_EVENTS_DLQ = "echomind.team.run-events.dlq";
    public static final String TEAM_CONTROL_DLQ = "echomind.team-control.dlq";
    public static final String TEAM_STEP_EXECUTE_DLQ = "echomind.team.step-execute.dlq";
    /** @deprecated split into {@link #TEAM_RUN_EVENTS_DLQ} and {@link #TEAM_STEP_EXECUTE_DLQ} */
    @Deprecated
    public static final String TEAM_COMMANDS_DLQ = TEAM_RUN_EVENTS_DLQ;

    private RabbitQueueNames() {
    }
}
