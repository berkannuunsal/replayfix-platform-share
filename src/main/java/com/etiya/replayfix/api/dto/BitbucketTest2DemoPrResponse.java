package com.etiya.replayfix.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record BitbucketTest2DemoPrResponse(
        UUID caseId,
        String jiraKey,
        boolean previewOnly,
        boolean created,
        String projectKey,
        String repositorySlug,
        String targetBranch,
        String integrationBranch,
        String generatedFilePath,
        String commitSha,
        String pullRequestId,
        String pullRequestUrl,
        String title,
        List<String> blockers,
        List<String> warnings,
        List<String> nextActions,
        Instant generatedAt
) {
    public BitbucketTest2DemoPrResponse {
        jiraKey = jiraKey == null ? "" : jiraKey;
        projectKey = projectKey == null ? "" : projectKey;
        repositorySlug = repositorySlug == null ? "" : repositorySlug;
        targetBranch = targetBranch == null ? "" : targetBranch;
        integrationBranch = integrationBranch == null ? "" : integrationBranch;
        generatedFilePath = generatedFilePath == null ? "" : generatedFilePath;
        commitSha = commitSha == null ? "" : commitSha;
        pullRequestId = pullRequestId == null ? "" : pullRequestId;
        pullRequestUrl = pullRequestUrl == null ? "" : pullRequestUrl;
        title = title == null ? "" : title;
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }
}
