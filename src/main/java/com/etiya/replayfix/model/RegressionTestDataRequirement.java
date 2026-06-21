package com.etiya.replayfix.model;

public record RegressionTestDataRequirement(
        String name,
        String description,
        boolean required,
        String source
) {
    public RegressionTestDataRequirement {
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        source = source == null ? "" : source;
    }
}
