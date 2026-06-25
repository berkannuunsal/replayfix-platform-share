package com.etiya.replaylab.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record JenkinsValidationResponse(
        UUID caseId,
        String defectKey,
        boolean previewOnly,
        boolean triggered,
        String jenkinsJobName,
        String jenkinsQueueUrl,
        String jenkinsBuildUrl,
        String sourceBranch,
        String targetBranch,
        String pullRequestId,
        Map<String, String> plannedParameters,
        List<String> guardrails,
        List<String> blockers,
        List<String> warnings,
        List<String> nextActions,
        Instant generatedAt
) {
}
