package com.etiya.replayfix.api.dto;

public record BitbucketWorkspacePushRequest(
        String requestedBy,
        String projectKey,
        String repositorySlug,
        String jiraKey,
        String workspaceId,
        String approvedWritePlanId,
        String developmentBaseBranch,
        String environmentTargetBranch,
        String bugfixBranch,
        String integrationBranch,
        String commitMessage,
        boolean confirmPush,
        boolean guardrailsAccepted
) {
}
