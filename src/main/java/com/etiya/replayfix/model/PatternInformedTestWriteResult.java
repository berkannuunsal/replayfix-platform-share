package com.etiya.replayfix.model;

import java.util.List;
import java.util.UUID;

public record PatternInformedTestWriteResult(
        UUID caseId,
        UUID approvalId,
        UUID candidateEvidenceId,
        String repositorySlug,
        String sourceCommitSha,
        String readiness,
        double compileConfidence,
        String originalProposedPath,
        String writtenRelativePath,
        String writtenAbsolutePath,
        int version,
        String contentSha256,
        int contentLength,
        boolean candidateHashVerified,
        boolean fileWritten,
        boolean testExecuted,
        boolean existingFileOverwritten,
        boolean gitDirty,
        String gitStatus,
        List<String> warnings
) {
}
