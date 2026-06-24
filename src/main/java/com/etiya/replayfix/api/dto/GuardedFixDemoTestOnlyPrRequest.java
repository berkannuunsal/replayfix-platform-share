package com.etiya.replayfix.api.dto;

public record GuardedFixDemoTestOnlyPrRequest(
        String requestedBy,
        String jiraKey,
        String projectKey,
        String repositorySlug,
        String developmentBaseBranch,
        String environmentTargetBranch,
        String bugfixBranch,
        String integrationBranch,
        String targetPrBranch,
        String commitMessage,
        boolean testOnly,
        boolean confirmExecute,
        boolean guardrailsAccepted
) {
}
