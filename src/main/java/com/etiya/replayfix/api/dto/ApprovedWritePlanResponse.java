package com.etiya.replayfix.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApprovedWritePlanResponse(
        UUID caseId,
        String jiraKey,
        String status,
        String writePlanStatus,
        boolean dryRun,
        boolean requiresHumanApproval,
        boolean approvalRequiredBeforeWrite,
        String targetRepository,
        String baseBranch,
        String proposedBranchName,
        String workspacePath,
        List<ApprovedWritePlanFile> plannedFiles,
        List<ApprovedWritePlanTest> plannedTests,
        List<String> plannedValidationCommands,
        List<String> missingEvidence,
        List<String> guardrails,
        List<String> warnings,
        Instant generatedAt
) {
    public ApprovedWritePlanResponse {
        jiraKey = jiraKey == null ? "" : jiraKey;
        status = status == null ? "HYPOTHESIS" : status;
        writePlanStatus = writePlanStatus == null
                ? "DRAFT"
                : writePlanStatus;
        targetRepository = targetRepository == null ? "" : targetRepository;
        baseBranch = baseBranch == null ? "" : baseBranch;
        proposedBranchName = proposedBranchName == null
                ? ""
                : proposedBranchName;
        workspacePath = workspacePath == null ? "" : workspacePath;
        plannedFiles = plannedFiles == null
                ? List.of()
                : List.copyOf(plannedFiles);
        plannedTests = plannedTests == null
                ? List.of()
                : List.copyOf(plannedTests);
        plannedValidationCommands = plannedValidationCommands == null
                ? List.of()
                : List.copyOf(plannedValidationCommands);
        missingEvidence = missingEvidence == null
                ? List.of()
                : List.copyOf(missingEvidence);
        guardrails = guardrails == null ? List.of() : List.copyOf(guardrails);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }
}
