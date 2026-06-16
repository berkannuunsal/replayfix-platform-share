package com.etiya.replayfix.model;

import java.util.List;

public record RootCauseDashboardView(
        String probableRootCause,
        String impactedComponent,
        double confidence,
        String confidenceBand,
        List<String> competingHypotheses,
        List<String> recommendedFixDirection,
        List<String> regressionTestHypothesis,
        String analysisType
) {
}
