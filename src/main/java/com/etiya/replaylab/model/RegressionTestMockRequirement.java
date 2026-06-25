package com.etiya.replaylab.model;

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
