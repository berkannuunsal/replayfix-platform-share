package com.etiya.replayfix.api.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record StepResponse(
        UUID caseId,
        String step,
        String status,
        String message,
        Map<String, Object> details,
        Instant completedAt
) {
    public static StepResponse success(UUID caseId, String step, String message, Map<String, Object> details) {
        return new StepResponse(caseId, step, "SUCCESS", message, details, Instant.now());
    }
}
