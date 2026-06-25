package com.etiya.replaylab.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record JenkinsValidationSummaryResponse(
        UUID caseId,
        String defectKey,
        String pullRequestId,
        String pullRequestUrl,
        String validationStatus,
        String summaryPreview,
        boolean prCommentCreated,
        String prCommentUrl,
        boolean jiraCommentCreated,
        List<String> blockers,
        List<String> warnings,
        List<String> nextActions,
        Instant generatedAt
) {
}
