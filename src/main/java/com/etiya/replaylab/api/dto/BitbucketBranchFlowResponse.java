package com.etiya.replaylab.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BitbucketBranchFlowResponse(
        UUID caseId,
        String jiraKey,
        boolean previewOnly,
        boolean executed,
        String projectKey,
        String repositorySlug,
        String developmentBaseBranch,
        String environmentTargetBranch,
        String bugfixBranch,
        String integrationBranch,
        boolean bugfixBranchCreated,
        boolean integrationBranchCreated,
        boolean mergeAttempted,
        boolean mergeSucceeded,
        boolean mergeConflict,
        List<String> blockers,
        List<String> warnings,
        List<String> nextActions,
        Instant generatedAt
) {
}
