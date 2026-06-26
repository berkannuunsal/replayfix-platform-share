package com.etiya.replaylab.api.dto;

import java.util.List;
import java.util.UUID;

public record ReplayLabDoraImpactScoreboardResponse(
        UUID caseId,
        String defectKey,
        String primaryMetric,
        List<String> secondaryMetrics,
        List<ScoreboardItem> scoreboard,
        int overallEstimatedReductionPercent,
        String measurementType,
        List<String> warnings
) {
    public record ScoreboardItem(
            String activity,
            int beforeMinutes,
            int afterMinutes,
            int reductionPercent,
            String evidence
    ) {
    }
}
