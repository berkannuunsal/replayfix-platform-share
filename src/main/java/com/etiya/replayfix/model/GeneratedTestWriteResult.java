package com.etiya.replayfix.model;

import java.util.List;
import java.util.UUID;

public record GeneratedTestWriteResult(
        UUID caseId,
        UUID approvalId,
        UUID planEvidenceId,
        String repositorySlug,
        String sourceCommitSha,
        String workspace,
        String relativePath,
        String absolutePath,
        String generatedClassName,
        String generatedMethodName,
        String contentSha256,
        int contentLength,
        boolean sourceEvidenceSaved,
        boolean fileWritten,
        boolean testExecuted,
        boolean gitDirty,
        String gitDiff,
        List<String> warnings
) {
}
