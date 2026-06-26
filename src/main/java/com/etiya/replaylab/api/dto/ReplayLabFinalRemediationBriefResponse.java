package com.etiya.replaylab.api.dto;

import java.util.List;
import java.util.UUID;

public record ReplayLabFinalRemediationBriefResponse(
        UUID caseId,
        String defectKey,
        String targetKey,
        String environment,
        ReplayLabDoraImpactScoreboardResponse doraImpact,
        ReplayLabRemediationReadinessResponse remediationReadiness,
        List<String> evidence,
        List<String> guardrails,
        List<String> nextActions,
        String markdown
) {
}
