package com.etiya.replaylab.api.dto;

import java.util.List;
import java.util.UUID;

public record PrRuleReviewResponse(
        UUID caseId,
        String defectKey,
        String repositoryType,
        String ruleSourceBranch,
        String reviewStatus,
        int blockerViolationCount,
        List<Violation> violations,
        String technicalReviewReport,
        List<String> rulesLoaded,
        List<String> ruleLookupBranchesTried,
        List<String> blockers,
        List<String> warnings,
        List<String> nextActions
) {
    public PrRuleReviewResponse(
            UUID caseId,
            String defectKey,
            String repositoryType,
            String reviewStatus,
            int blockerViolationCount,
            List<Violation> violations,
            String technicalReviewReport,
            List<String> rulesLoaded,
            List<String> blockers,
            List<String> warnings,
            List<String> nextActions
    ) {
        this(
                caseId,
                defectKey,
                repositoryType,
                "",
                reviewStatus,
                blockerViolationCount,
                violations,
                technicalReviewReport,
                rulesLoaded,
                List.of(),
                blockers,
                warnings,
                nextActions
        );
    }

    public record Violation(
            String ruleId,
            String severity,
            String category,
            String message,
            List<Evidence> evidence
    ) {
    }

    public record Evidence(
            String filePath,
            String line
    ) {
    }
}
