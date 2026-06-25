package com.etiya.replaylab.api.dto;

public record ReplayEnvironmentComponentHintResult(
        String componentKey,
        String requestedMode,
        String reason,
        String message
) {
    public ReplayEnvironmentComponentHintResult {
        componentKey = componentKey == null ? "" : componentKey;
        requestedMode = requestedMode == null ? "" : requestedMode;
        reason = reason == null ? "" : reason;
        message = message == null ? "" : message;
    }
}
