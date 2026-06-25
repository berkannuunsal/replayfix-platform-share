package com.etiya.replaylab.model;

import java.util.UUID;

public record GeneratedTestSource(
        UUID caseId,
        UUID approvalId,
        UUID planEvidenceId,
        String repositorySlug,
        String sourceCommitSha,
        String relativePath,
        String className,
        String methodName,
        String language,
        String framework,
        String content,
        String contentSha256,
        String generationMode,
        boolean approved,
        boolean written
) {
}
