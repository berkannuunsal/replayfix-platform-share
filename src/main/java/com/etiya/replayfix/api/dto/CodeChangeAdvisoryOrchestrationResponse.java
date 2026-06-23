package com.etiya.replayfix.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CodeChangeAdvisoryOrchestrationResponse(
        UUID caseId,
        String jiraKey,
        String targetKey,
        String orchestrationStatus,
        boolean dryRun,
        int requestedCandidateCount,
        int processedCandidateCount,
        int advisoryResultCount,
        int skippedCandidateCount,
        List<String> blockers,
        List<String> warnings,
        List<String> missingEvidence,
        List<String> nextActions,
        List<CodeChangeAdvisoryResultSummary> results,
        CodeChangeAdvisoryEvaluationSummaryResponse evaluationSummary,
        Instant generatedAt
) {
    public CodeChangeAdvisoryOrchestrationResponse {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        missingEvidence = missingEvidence == null
                ? List.of()
                : List.copyOf(missingEvidence);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
        results = results == null ? List.of() : List.copyOf(results);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }
}
