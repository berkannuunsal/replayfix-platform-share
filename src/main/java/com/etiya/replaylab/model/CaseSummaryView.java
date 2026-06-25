package com.etiya.replaylab.model;

import java.time.Instant;
import java.util.UUID;

public record CaseSummaryView(
        UUID caseId,
        String jiraKey,
        String summary,
        String status,
        String priority,
        String affectedApplication,
        String businessImpact,
        String technicalSymptom,
        Instant createdAt,
        Instant updatedAt
) {
}
