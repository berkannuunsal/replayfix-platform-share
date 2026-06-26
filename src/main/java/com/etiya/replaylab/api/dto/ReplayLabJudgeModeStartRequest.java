package com.etiya.replaylab.api.dto;

public record ReplayLabJudgeModeStartRequest(
        String requestedBy,
        String defectKey,
        String targetKey,
        String environment,
        boolean demoMode
) {
}
