package com.echomind.console.sensitive;

public class SensitiveDataBlockedException extends RuntimeException {

    private final SensitiveDirection direction;
    private final String ruleName;

    public SensitiveDataBlockedException(SensitiveDirection direction, String ruleName) {
        super("Sensitive data blocked in " + direction.name().toLowerCase() + ": " + ruleName);
        this.direction = direction;
        this.ruleName = ruleName;
    }

    public SensitiveDirection direction() {
        return direction;
    }

    public String ruleName() {
        return ruleName;
    }
}
