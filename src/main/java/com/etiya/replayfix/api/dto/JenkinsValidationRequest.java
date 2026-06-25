package com.etiya.replayfix.api.dto;

import java.util.List;

public record JenkinsValidationRequest(
        String requestedBy,
        String projectKey,
        String repositorySlug,
        String repositoryType,
        String defectKey,
        String defectSummary,
        String pullRequestId,
        String pullRequestUrl,
        String sourceBranch,
        String targetBranch,
        List<String> changedFiles,
        String validationType,
        boolean confirmTrigger,
        boolean guardrailsAccepted
) {
}
