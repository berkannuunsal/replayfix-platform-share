package com.etiya.replaylab.api.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ReplayLabJudgeModeStartResponse(
        UUID caseId,
        String defectKey,
        String targetKey,
        String environment,
        boolean demoMode,
        ReplayLabDoraImpactScoreboardResponse doraImpact,
        ReplayLabRemediationReadinessResponse remediationReadiness,
        Map<String, Object> agentsPreflightSummary,
        Map<String, Object> targetedPrPreviewSummary,
        String finalBriefMarkdown,
        List<String> guardrails,
        List<String> nextActions
) {
}
