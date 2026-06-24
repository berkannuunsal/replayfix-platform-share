package com.etiya.replayfix.api.dto;

import java.util.List;
import java.util.UUID;

public record ReplayEnvironmentComponentHintsResponse(
        UUID caseId,
        String jiraKey,
        String targetKey,
        String status,
        List<ReplayEnvironmentComponentHintResult> acceptedHints,
        List<ReplayEnvironmentComponentHintResult> rejectedHints,
        List<String> warnings
) {
    public ReplayEnvironmentComponentHintsResponse {
        jiraKey = jiraKey == null ? "" : jiraKey;
        targetKey = targetKey == null ? "" : targetKey;
        status = status == null ? "ACCEPTED" : status;
        acceptedHints = acceptedHints == null
                ? List.of()
                : List.copyOf(acceptedHints);
        rejectedHints = rejectedHints == null
                ? List.of()
                : List.copyOf(rejectedHints);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
