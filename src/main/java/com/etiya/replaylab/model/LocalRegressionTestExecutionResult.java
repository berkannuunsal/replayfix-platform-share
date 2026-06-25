package com.etiya.replaylab.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LocalRegressionTestExecutionResult(
        UUID caseId,
        UUID approvalId,
        UUID writeResultEvidenceId,
        String repositorySlug,
        String workspace,
        String relativeTestPath,
        String testClass,
        String testMethod,
        String testSelector,
        String executable,
        List<String> arguments,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        Integer exitCode,
        boolean timedOut,
        boolean generatedFileHashVerified,
        boolean testExecuted,
        LocalTestExecutionStatus status,
        boolean defectReproduced,
        boolean scaffoldFailure,
        String output,
        List<String> matchedSignals,
        List<String> warnings
) {
}
