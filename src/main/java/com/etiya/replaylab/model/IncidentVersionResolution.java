package com.etiya.replaylab.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record IncidentVersionResolution(
        UUID caseId,
        String repositorySlug,
        String branch,
        String strategy,
        String requestedSourceCommit,
        String requestedImageTag,
        Instant incidentTime,
        String resolvedCommitSha,
        String resolvedTag,
        Instant commitTime,
        String commitMessage,
        boolean exactMatch,
        List<String> warnings
) {
}
