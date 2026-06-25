package com.etiya.replaylab.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record BitbucketPullRequestResponse(
        UUID caseId,
        String jiraKey,
        boolean previewOnly,
        boolean created,
        String pullRequestUrl,
        String pullRequestId,
        String projectKey,
        String repositorySlug,
        String sourceBranch,
        String targetBranch,
        String title,
        String descriptionPreview,
        Map<String, Object> bitbucketPayloadPreview,
        List<String> blockers,
        List<String> warnings,
        List<String> nextActions,
        Instant generatedAt
) {
}
