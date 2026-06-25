package com.etiya.replaylab.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ReplayEnvironmentLlmAdvisoryResponse(
        UUID requestId,
        UUID caseId,
        String jiraKey,
        String targetKey,
        String advisoryMode,
        boolean llmUsed,
        String llmStatus,
        String advisoryStatus,
        Map<String, Object> advisory,
        List<String> blockers,
        List<String> warnings,
        List<String> nextActions,
        String safeOutputPreview,
        Instant generatedAt
) {
    public ReplayEnvironmentLlmAdvisoryResponse {
        advisory = advisory == null ? Map.of() : Map.copyOf(advisory);
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        nextActions = nextActions == null ? List.of() : List.copyOf(nextActions);
        safeOutputPreview = safeOutputPreview == null ? "" : safeOutputPreview;
    }
}
