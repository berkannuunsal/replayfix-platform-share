package com.etiya.replaylab.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record JiraFinalUpdatePreviewResponse(
        UUID caseId,
        String jiraKey,
        String status,
        boolean previewOnly,
        boolean shouldPublish,
        boolean requiresHumanApproval,
        List<JiraFinalUpdateCommentSection> commentSections,
        List<String> missingEvidence,
        List<String> warnings,
        Instant generatedAt
) {
    public JiraFinalUpdatePreviewResponse {
        jiraKey = jiraKey == null ? "" : jiraKey;
        status = status == null ? "HYPOTHESIS" : status;
        commentSections = commentSections == null
                ? List.of()
                : List.copyOf(commentSections);
        missingEvidence = missingEvidence == null
                ? List.of()
                : List.copyOf(missingEvidence);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
        generatedAt = generatedAt == null ? Instant.now() : generatedAt;
    }
}
