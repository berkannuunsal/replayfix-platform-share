package com.etiya.replaylab.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GeneratedDraftReviewResponse(
        UUID caseId,
        String jiraKey,
        String status,
        String reviewStatus,
        String workspacePath,
        List<GeneratedDraftReviewedFile> reviewedFiles,
        List<String> securityFindings,
        List<String> qualityFindings,
        List<String> missingItems,
        List<String> recommendedNextActions,
        boolean requiresHumanApproval,
        boolean shouldRunTests,
        boolean shouldCreateBranch,
        boolean shouldOpenPr,
        List<String> warnings,
        Instant generatedAt
) {
    public GeneratedDraftReviewResponse {
        jiraKey = jiraKey == null ? "" : jiraKey;
        status = status == null ? "HYPOTHESIS" : status;
        reviewStatus = reviewStatus == null ? "NO_GENERATED_FILES" : reviewStatus;
        workspacePath = workspacePath == null ? "" : workspacePath;
        reviewedFiles = reviewedFiles == null
                ? List.of()
                : List.copyOf(reviewedFiles);
        securityFindings = securityFindings == null
                ? List.of()
                : List.copyOf(securityFindings);
        qualityFindings = qualityFindings == null
                ? List.of()
                : List.copyOf(qualityFindings);
        missingItems = missingItems == null
                ? List.of()
                : List.copyOf(missingItems);
        recommendedNextActions = recommendedNextActions == null
                ? List.of()
                : List.copyOf(recommendedNextActions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }
}
