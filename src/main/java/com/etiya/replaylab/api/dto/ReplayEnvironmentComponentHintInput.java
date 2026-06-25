package com.etiya.replaylab.api.dto;

public record ReplayEnvironmentComponentHintInput(
        String componentKey,
        String requestedMode,
        String reason
) {
}
