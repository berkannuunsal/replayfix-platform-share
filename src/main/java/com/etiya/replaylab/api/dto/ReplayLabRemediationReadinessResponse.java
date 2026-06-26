package com.etiya.replaylab.api.dto;

import java.util.List;
import java.util.UUID;

public record ReplayLabRemediationReadinessResponse(
        UUID caseId,
        String defectKey,
        int score,
        String verdict,
        List<ScoreBreakdownItem> scoreBreakdown,
        List<String> guardrails,
        List<String> nextActions
) {
    public record ScoreBreakdownItem(
            String category,
            int points,
            int maxPoints,
            String reason
    ) {
    }
}
