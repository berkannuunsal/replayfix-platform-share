package com.etiya.replayfix.api.dto;

import java.util.List;

public record BitbucketPullRequestRequest(
        String requestedBy,
        String projectKey,
        String repositorySlug,
        String sourceBranch,
        String targetBranch,
        String titlePrefix,
        boolean includeCodeAdvisory,
        boolean includePatchPlan,
        boolean includeTestPlan,
        List<String> reviewerUsers,
        boolean confirmCreate,
        boolean guardrailsAccepted
) {
}
