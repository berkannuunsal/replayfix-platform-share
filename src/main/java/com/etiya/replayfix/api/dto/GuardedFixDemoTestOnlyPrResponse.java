package com.etiya.replayfix.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GuardedFixDemoTestOnlyPrResponse(
        UUID caseId,
        String jiraKey,
        boolean executed,
        boolean testOnly,
        String generatedFilePath,
        String bugfixBranch,
        String integrationBranch,
        String commitSha,
        String pullRequestUrl,
        String pullRequestId,
        List<String> blockers,
        List<String> warnings,
        List<String> nextActions,
        Instant generatedAt
) {
    public GuardedFixDemoTestOnlyPrResponse {
        jiraKey = jiraKey == null ? "" : jiraKey;
        generatedFilePath = generatedFilePath == null ? "" : generatedFilePath;
        bugfixBranch = bugfixBranch == null ? "" : bugfixBranch;
        integrationBranch = integrationBranch == null ? "" : integrationBranch;
        commitSha = commitSha == null ? "" : commitSha;
        pullRequestUrl = pullRequestUrl == null ? "" : pullRequestUrl;
        pullRequestId = pullRequestId == null ? "" : pullRequestId;
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }
}
