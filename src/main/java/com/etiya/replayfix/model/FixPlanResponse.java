package com.etiya.replayfix.model;

import java.util.List;
import java.util.UUID;

public record FixPlanResponse(
        UUID caseId,
        String jiraKey,
        String status,
        double confidence,
        List<FixPlanCandidate> fixCandidates,
        FixPlanCandidate selectedCandidate,
        List<FixPlanEvidenceReference> requiredEvidence,
        List<String> missingEvidence,
        boolean requiresDbEvidence,
        boolean requiresHumanApproval,
        List<String> warnings
) {
    public FixPlanResponse {
        jiraKey = jiraKey == null ? "" : jiraKey;
        status = status == null ? "HYPOTHESIS" : status;
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        fixCandidates = fixCandidates == null ? List.of() : List.copyOf(fixCandidates);
        requiredEvidence = requiredEvidence == null
                ? List.of()
                : List.copyOf(requiredEvidence);
        missingEvidence = missingEvidence == null ? List.of() : List.copyOf(missingEvidence);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
