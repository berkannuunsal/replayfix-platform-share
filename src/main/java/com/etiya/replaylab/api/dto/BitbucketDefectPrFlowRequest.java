package com.etiya.replaylab.api.dto;

public record BitbucketDefectPrFlowRequest(
        String requestedBy,
        String projectKey,
        String repositorySlug,
        String defectKey,
        String defectSummary,
        String environment,
        String sourceBaseBranch,
        String targetBaseBranch,
        String bugfixBranch,
        String integrationBranch,
        String titlePrefix,
        boolean allowReuseExistingBranches,
        boolean applyApprovedFix,
        boolean applyApprovedRegressionTest,
        boolean applyApprovedConfigChange,
        boolean confirmCreate,
        boolean guardrailsAccepted
) {
}
