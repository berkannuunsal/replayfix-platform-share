package com.etiya.replaylab.api.dto;

public record JenkinsValidationResultRefreshRequest(
        String requestedBy,
        String defectKey,
        String jenkinsJobName,
        String jenkinsQueueUrl,
        String jenkinsBuildUrl,
        String buildNumber,
        String pullRequestId,
        String pullRequestUrl,
        String sourceBranch,
        String targetBranch
) {
}
