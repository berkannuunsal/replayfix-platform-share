package com.etiya.replayfix.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TestExecutionPlanResponse(
        UUID caseId,
        String jiraKey,
        String status,
        String executionPlanStatus,
        boolean dryRun,
        boolean requiresHumanApproval,
        boolean shouldRunTests,
        String workspacePath,
        List<String> testCommands,
        List<String> testTargets,
        List<String> requiredProfiles,
        List<String> requiredMocks,
        List<String> requiredDbEvidence,
        List<String> blockers,
        List<String> guardrails,
        List<String> warnings,
        Instant generatedAt
) {
    public TestExecutionPlanResponse {
        jiraKey = jiraKey == null ? "" : jiraKey;
        status = status == null ? "HYPOTHESIS" : status;
        executionPlanStatus = executionPlanStatus == null
                ? "BLOCKED_BY_MISSING_APPROVAL"
                : executionPlanStatus;
        workspacePath = workspacePath == null ? "" : workspacePath;
        testCommands = testCommands == null
                ? List.of()
                : List.copyOf(testCommands);
        testTargets = testTargets == null ? List.of() : List.copyOf(testTargets);
        requiredProfiles = requiredProfiles == null
                ? List.of()
                : List.copyOf(requiredProfiles);
        requiredMocks = requiredMocks == null
                ? List.of()
                : List.copyOf(requiredMocks);
        requiredDbEvidence = requiredDbEvidence == null
                ? List.of()
                : List.copyOf(requiredDbEvidence);
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        guardrails = guardrails == null ? List.of() : List.copyOf(guardrails);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }
}
