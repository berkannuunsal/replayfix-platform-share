package com.etiya.replayfix.api.dto;

public record ReplayEnvironmentComponentHintInput(
        String componentKey,
        String requestedMode,
        String reason
) {
}
