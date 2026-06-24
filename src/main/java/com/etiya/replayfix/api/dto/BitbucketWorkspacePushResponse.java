package com.etiya.replayfix.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BitbucketWorkspacePushResponse(
        UUID caseId,
        String jiraKey,
        boolean previewOnly,
        boolean executed,
        String projectKey,
        String repositorySlug,
        String bugfixBranch,
        String integrationBranch,
        String commitSha,
        boolean bugfixBranchPushed,
        boolean integrationBranchPrepared,
        boolean mergeAttempted,
        boolean mergeSucceeded,
        boolean mergeConflict,
        List<String> blockers,
        List<String> warnings,
        List<String> nextActions,
        Instant generatedAt
) {
}
