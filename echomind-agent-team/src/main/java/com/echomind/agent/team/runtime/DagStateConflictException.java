package com.echomind.agent.team.runtime;

/**
 * Thrown when a DAG state transition fails because another event already changed the state.
 * This is a business exception, not an infrastructure error — the message should go to DLQ.
 */
public class DagStateConflictException extends RuntimeException {

    public DagStateConflictException(String message) {
        super(message);
    }

    public DagStateConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
