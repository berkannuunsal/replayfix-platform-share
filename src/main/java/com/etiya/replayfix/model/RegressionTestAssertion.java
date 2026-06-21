package com.etiya.replayfix.model;

public record RegressionTestAssertion(
        String scope,
        String description,
        String expected
) {
    public RegressionTestAssertion {
        scope = scope == null ? "" : scope;
        description = description == null ? "" : description;
        expected = expected == null ? "" : expected;
    }
}
