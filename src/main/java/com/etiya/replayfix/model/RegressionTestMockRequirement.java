package com.etiya.replayfix.model;

public record RegressionTestMockRequirement(
        String target,
        String reason,
        boolean required
) {
    public RegressionTestMockRequirement {
        target = target == null ? "" : target;
        reason = reason == null ? "" : reason;
    }
}
