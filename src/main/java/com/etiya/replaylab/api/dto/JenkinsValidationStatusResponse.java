package com.etiya.replaylab.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record JenkinsValidationStatusResponse(
        UUID caseId,
        String defectKey,
        String validationStatus,
        String jenkinsJobName,
        String jenkinsQueueUrl,
        String jenkinsBuildUrl,
        String buildNumber,
        String startedAt,
        String finishedAt,
        long durationMillis,
        String sourceBranch,
        String targetBranch,
        String pullRequestId,
        List<String> blockers,
        List<String> warnings,
        List<String> nextActions,
        Instant generatedAt
) {
}
