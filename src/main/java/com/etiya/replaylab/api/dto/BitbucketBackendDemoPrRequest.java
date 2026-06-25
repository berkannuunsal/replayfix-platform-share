package com.etiya.replaylab.api.dto;

public record BitbucketBackendDemoPrRequest(
        String requestedBy,
        String projectKey,
        String repositorySlug,
        String defectNo,
        String defectSummary,
        String sourceBaseBranch,
        String targetBaseBranch,
        String bugfixBranch,
        String integrationBranch,
        String titlePrefix,
        boolean testOnly,
        boolean allowReuseExistingBranches,
        boolean confirmCreate,
        boolean guardrailsAccepted
) {
}
