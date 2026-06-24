package com.etiya.replayfix.api.dto;

public record BitbucketTest2DemoPrRequest(
        String requestedBy,
        String projectKey,
        String repositorySlug,
        String jiraKey,
        String targetBranch,
        String integrationBranch,
        String titlePrefix,
        boolean testOnly,
        boolean allowReuseExistingIntegrationBranch,
        boolean confirmCreate,
        boolean guardrailsAccepted
) {
}
