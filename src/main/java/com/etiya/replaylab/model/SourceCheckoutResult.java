package com.etiya.replaylab.model;

import java.util.UUID;

public record SourceCheckoutResult(
        UUID caseId,
        String projectKey,
        String repositorySlug,
        String branch,
        String commitSha,
        String workspace,
        boolean reused
) {
}
