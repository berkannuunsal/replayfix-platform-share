package com.etiya.replayfix.api.dto;

import java.util.List;

public record JenkinsValidationSummaryRequest(
        String requestedBy,
        String projectKey,
        String repositorySlug,
        String defectKey,
        String defectSummary,
        String pullRequestId,
        String pullRequestUrl,
        String sourceBranch,
        String targetBranch,
        String jenkinsJobName,
        String jenkinsBuildUrl,
        String validationStatus,
        List<String> changedFiles,
        String agentsPreflightStatus,
        int blockerViolationCount
) {
}
