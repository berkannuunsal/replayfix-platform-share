package com.etiya.replaylab.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TestExecutionGuardResponse(
        UUID caseId,
        String jiraKey,
        String status,
        String executionStatus,
        boolean dryRun,
        boolean requiresHumanApproval,
        boolean approvalPresent,
        boolean commandExecuted,
        List<String> testCommands,
        List<String> testTargets,
        List<String> blockers,
        List<String> guardrails,
        List<String> warnings,
        Instant generatedAt
) {
    public TestExecutionGuardResponse {
        jiraKey = jiraKey == null ? "" : jiraKey;
        status = status == null ? "HYPOTHESIS" : status;
        executionStatus = executionStatus == null
                ? "PREVIEW_READY"
                : executionStatus;
        testCommands = testCommands == null
                ? List.of()
                : List.copyOf(testCommands);
        testTargets = testTargets == null ? List.of() : List.copyOf(testTargets);
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        guardrails = guardrails == null ? List.of() : List.copyOf(guardrails);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }
}
