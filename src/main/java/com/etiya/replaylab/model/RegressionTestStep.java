package com.etiya.replaylab.model;

public record RegressionTestStep(
        int order,
        String action,
        String input,
        String expectedOutcome
) {
    public RegressionTestStep {
        action = action == null ? "" : action;
        input = input == null ? "" : input;
        expectedOutcome = expectedOutcome == null ? "" : expectedOutcome;
    }
}
