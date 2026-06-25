package com.etiya.replaylab.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record PatchPlanCandidateResponse(
        UUID caseId,
        String jiraKey,
        String status,
        String patchPlanStatus,
        boolean shouldProceedToPatch,
        boolean requiresHumanApproval,
        String targetRepository,
        String targetBranch,
        String proposedBranchName,
        List<String> targetFiles,
        List<String> targetMethods,
        String targetEndpoint,
        String targetClass,
        String targetMethod,
        String recommendedChangeType,
        Map<String, Object> recommendedCodeChange,
        List<Map<String, Object>> riskReview,
        List<Map<String, Object>> testPlan,
        List<String> dbValidationRequirements,
        Map<String, Object> replayReadiness,
        List<String> missingEvidence,
        List<String> warnings,
        Instant generatedAt
) {
    public PatchPlanCandidateResponse {
        jiraKey = jiraKey == null ? "" : jiraKey;
        status = status == null ? "HYPOTHESIS" : status;
        patchPlanStatus = patchPlanStatus == null ? "DRAFT" : patchPlanStatus;
        targetRepository = targetRepository == null ? "" : targetRepository;
        targetBranch = targetBranch == null ? "" : targetBranch;
        proposedBranchName = proposedBranchName == null
                ? ""
                : proposedBranchName;
        targetFiles = targetFiles == null ? List.of() : List.copyOf(targetFiles);
        targetMethods = targetMethods == null
                ? List.of()
                : List.copyOf(targetMethods);
        targetEndpoint = targetEndpoint == null ? "" : targetEndpoint;
        targetClass = targetClass == null ? "" : targetClass;
        targetMethod = targetMethod == null ? "" : targetMethod;
        recommendedChangeType = recommendedChangeType == null
                ? "UNKNOWN"
                : recommendedChangeType;
        recommendedCodeChange = recommendedCodeChange == null
                ? Map.of()
                : Map.copyOf(recommendedCodeChange);
        riskReview = riskReview == null ? List.of() : List.copyOf(riskReview);
        testPlan = testPlan == null ? List.of() : List.copyOf(testPlan);
        dbValidationRequirements = dbValidationRequirements == null
                ? List.of()
                : List.copyOf(dbValidationRequirements);
        replayReadiness = replayReadiness == null
                ? Map.of()
                : Map.copyOf(replayReadiness);
        missingEvidence = missingEvidence == null
                ? List.of()
                : List.copyOf(missingEvidence);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }
}
