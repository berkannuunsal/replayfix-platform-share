package com.etiya.replaylab.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RegressionTestHypothesis(
        String schemaVersion,
        UUID caseId,
        String jiraKey,
        String source,
        Instant generatedAt,
        String status,
        String testType,
        String targetFlow,
        String targetComponent,
        String probableRootCause,
        Double confidence,
        String failingScenario,
        List<String> preconditions,
        List<String> suggestedInputs,
        List<String> expectedFailureSignals,
        List<String> assertions,
        List<String> mocksOrDependencies,
        List<String> missingEvidence,
        List<String> sourceEvidence,
        Map<String, Object> rovoSummary,
        boolean fileWritten,
        boolean testExecuted,
        boolean writeAuthorized,
        boolean executionAuthorized,
        boolean humanApprovalRequired,
        List<String> warnings
) {
    public static final String SCHEMA_VERSION = "1.0";
}
