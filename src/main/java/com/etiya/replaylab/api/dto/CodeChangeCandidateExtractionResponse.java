package com.etiya.replaylab.api.dto;

import java.util.List;
import java.util.UUID;

public record CodeChangeCandidateExtractionResponse(
        UUID caseId,
        String jiraKey,
        String targetKey,
        String sourceCandidateSource,
        int candidateCount,
        List<CodeChangeCandidateSummary> candidates,
        List<String> blockers,
        List<String> warnings,
        List<String> missingEvidence
) {
    public CodeChangeCandidateExtractionResponse {
        sourceCandidateSource = sourceCandidateSource == null
                ? ""
                : sourceCandidateSource;
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        missingEvidence = missingEvidence == null
                ? List.of()
                : List.copyOf(missingEvidence);
    }

    public CodeChangeCandidateExtractionResponse(
            UUID caseId,
            String jiraKey,
            String targetKey,
            int candidateCount,
            List<CodeChangeCandidateSummary> candidates,
            List<String> blockers,
            List<String> warnings,
            List<String> missingEvidence
    ) {
        this(
                caseId,
                jiraKey,
                targetKey,
                "",
                candidateCount,
                candidates,
                blockers,
                warnings,
                missingEvidence
        );
    }
}
