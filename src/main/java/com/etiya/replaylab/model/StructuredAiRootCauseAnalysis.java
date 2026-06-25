package com.etiya.replaylab.model;

import java.util.List;
import java.util.UUID;

public record StructuredAiRootCauseAnalysis(
        UUID caseId,
        String analysisType,
        String executiveSummary,
        String probableRootCause,
        String impactedComponent,
        double confidence,
        List<String> failureChain,
        List<String> supportingEvidenceIds,
        List<String> competingHypotheses,
        List<String> regressionTestHypothesis,
        List<String> minimumFixDirection,
        List<String> missingEvidence,
        String recommendedNextAction,
        String provider,
        String model,
        boolean synthetic,
        List<String> warnings
) {}
