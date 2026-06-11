package com.echomind.console.deadletter;

public final class RabbitDeadLetterStatus {

    public static final String ARCHIVED = "ARCHIVED";
    public static final String COMPENSATED = "COMPENSATED";
    public static final String REPLAYED = "REPLAYED";
    public static final String REPLAY_FAILED = "REPLAY_FAILED";

    private RabbitDeadLetterStatus() {
    }
}
