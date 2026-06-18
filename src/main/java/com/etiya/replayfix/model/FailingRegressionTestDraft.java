package com.etiya.replayfix.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record FailingRegressionTestDraft(
        String schemaVersion,
        UUID caseId,
        UUID hypothesisEvidenceId,
        String jiraKey,
        String source,
        Instant generatedAt,
        String status,
        String readiness,
        String testType,
        String proposedRelativePath,
        String proposedPackage,
        String proposedClassName,
        String proposedMethodName,
        String language,
        String framework,
        String sourceCode,
        String contentSha256,
        String targetFlow,
        String probableRootCause,
        List<String> expectedFailureSignals,
        List<String> assertions,
        List<String> sourceEvidence,
        List<String> assumptions,
        List<String> warnings,
        boolean fileWritten,
        boolean testExecuted,
        boolean writeAuthorized,
        boolean executionAuthorized,
        boolean humanApprovalRequired
) {
    public static final String SCHEMA_VERSION = "1.0";
}
