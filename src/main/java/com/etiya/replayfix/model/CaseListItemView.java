package com.etiya.replayfix.model;

import java.time.Instant;
import java.util.UUID;

public record CaseListItemView(
        UUID caseId,
        String jiraKey,
        String summary,
        String application,
        String workflowStatus,
        Double rootCauseConfidence,
        Instant createdAt,
        Instant lastUpdated
) {
}
