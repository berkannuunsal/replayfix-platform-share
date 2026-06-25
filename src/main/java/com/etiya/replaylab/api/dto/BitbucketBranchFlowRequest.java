package com.etiya.replaylab.api.dto;

public record BitbucketBranchFlowRequest(
        String requestedBy,
        String projectKey,
        String repositorySlug,
        String jiraKey,
        String developmentBaseBranch,
        String environmentTargetBranch,
        String bugfixBranch,
        String integrationBranch,
        boolean createBugfixBranchFromMaster,
        boolean createIntegrationBranchFromTarget,
        boolean mergeBugfixIntoIntegration,
        boolean allowReuseExistingBranches,
        boolean confirmCreate,
        boolean guardrailsAccepted
) {
}
