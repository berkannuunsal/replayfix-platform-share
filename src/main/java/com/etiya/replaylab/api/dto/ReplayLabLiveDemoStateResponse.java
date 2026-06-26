package com.etiya.replaylab.api.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ReplayLabLiveDemoStateResponse(
        UUID caseId,
        String defectKey,
        String defectTitle,
        String targetKey,
        String environment,
        String currentStep,
        List<String> completedSteps,
        List<String> unlockedSteps,
        List<ReplayLabAgentEvent> agentEvents,
        List<ReplayLabEvidenceDetail> evidence,
        List<ReplayLabEvidenceDetail> humanEvidence,
        ReplayLabTokenUsageEstimateResponse tokenUsage,
        ReplayLabDoraImpactScoreboardResponse doraImpact,
        ReplayLabRemediationReadinessResponse remediationReadiness,
        ReplayLabEnvironmentBlueprintResponse environmentBlueprint,
        Map<String, Object> preflightSummary,
        Map<String, Object> targetedPrPreview,
        Map<String, Object> jiraTestCasePreview,
        ReplayLabRcaResponse rootCauseAnalysis,
        String finalBriefMarkdown,
        List<String> guardrails,
        boolean fallbackUsed,
        List<String> warnings,
        List<String> nextActions
) {
}
