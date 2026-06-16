package com.etiya.replayfix.model;

import java.time.Instant;
import java.util.UUID;

public record WorkflowStepView(
        UUID id,
        String stepName,
        String status,
        int attempt,
        int maxAttempts,
        Instant startedAt,
        Instant finishedAt,
        Instant nextRetryAt,
        String evidenceType,
        String evidenceSource,
        String errorCategory,
        String errorMessage,
        String resultSummary
) {
}
