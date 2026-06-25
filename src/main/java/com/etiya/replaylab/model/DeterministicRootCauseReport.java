package com.etiya.replaylab.model;

import java.util.List;

public record DeterministicRootCauseReport(
        String jiraKey,
        String status,
        String classification,
        String probableCause,
        double confidence,
        List<String> affectedApplications,
        List<String> supportingEvidence,
        List<String> missingEvidence,
        List<String> recommendedActions,
        RootCauseMetrics metrics
) {
}
