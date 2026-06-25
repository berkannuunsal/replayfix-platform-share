package com.etiya.replaylab.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkspaceWriteResponse(
        UUID caseId,
        String jiraKey,
        String status,
        String writeStatus,
        boolean dryRun,
        List<WorkspaceWriteFileResult> filesWritten,
        List<WorkspaceWriteFileResult> filesPlanned,
        String workspacePath,
        boolean requiresHumanApproval,
        boolean approvalPresent,
        List<String> guardrails,
        List<String> warnings,
        Instant generatedAt
) {
    public WorkspaceWriteResponse {
        jiraKey = jiraKey == null ? "" : jiraKey;
        status = status == null ? "HYPOTHESIS" : status;
        writeStatus = writeStatus == null ? "PREVIEW_READY" : writeStatus;
        filesWritten = filesWritten == null
                ? List.of()
                : List.copyOf(filesWritten);
        filesPlanned = filesPlanned == null
                ? List.of()
                : List.copyOf(filesPlanned);
        workspacePath = workspacePath == null ? "" : workspacePath;
        guardrails = guardrails == null ? List.of() : List.copyOf(guardrails);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }
}
