package com.etiya.replaylab.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkflowRunView(
        UUID id,
        UUID caseId,
        String triggerType,
        String triggerReference,
        String status,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt,
        int successfulStepCount,
        int failedStepCount,
        int skippedStepCount,
        UUID jiraPreviewEvidenceId,
        String summary,
        List<WorkflowStepView> steps
) {
}
