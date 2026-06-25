package com.etiya.replaylab.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GuardedFixDemoPreviewResponse(
        UUID caseId,
        String jiraKey,
        String demoStatus,
        String recommendedPath,
        List<GuardedFixDemoStageResponse> stages,
        List<String> overallBlockers,
        List<String> overallWarnings,
        String recommendedNextAction,
        Instant generatedAt
) {
    public GuardedFixDemoPreviewResponse {
        jiraKey = jiraKey == null ? "" : jiraKey;
        demoStatus = demoStatus == null ? "PARTIAL" : demoStatus;
        recommendedPath = recommendedPath == null
                ? "PREVIEW_ONLY"
                : recommendedPath;
        stages = stages == null ? List.of() : List.copyOf(stages);
        overallBlockers = overallBlockers == null
                ? List.of()
                : List.copyOf(overallBlockers);
        overallWarnings = overallWarnings == null
                ? List.of()
                : List.copyOf(overallWarnings);
        recommendedNextAction = recommendedNextAction == null
                ? ""
                : recommendedNextAction;
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }
}
