package com.etiya.replaylab.api.dto;

import java.time.Instant;
import java.util.UUID;

public record CodeChangeAdvisoryEvaluationSummaryResponse(
        UUID caseId,
        String jiraKey,
        String targetKey,
        int advisoryGeneratedCount,
        CodeChangeAdvisoryResultSummary latestBackendMethodAdvisory,
        CodeChangeAdvisoryResultSummary latestFrontendAdvisory,
        CodeChangeAdvisoryResultSummary latestTestSuggestion,
        CodeChangeAdvisoryResultSummary latestRiskReview,
        double averageConfidence,
        int actionableRecommendationCount,
        int missingEvidenceCount,
        int shouldProceedToPatchCount,
        String caseAdvisoryStatus,
        Instant generatedAt
) {
}
