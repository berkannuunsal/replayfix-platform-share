package com.etiya.replaylab.model;

public record SourceFlowAnchor(
        String value,
        String type,
        String strength,
        boolean primary,
        String reason
) {
    public SourceFlowAnchor(String value, String type, String reason) {
        this(value, type, "STRONG", true, reason);
    }

    public SourceFlowAnchor {
        value = value == null ? "" : value;
        type = type == null ? "" : type;
        strength = strength == null ? "WEAK" : strength;
        reason = reason == null ? "" : reason;
    }
}
