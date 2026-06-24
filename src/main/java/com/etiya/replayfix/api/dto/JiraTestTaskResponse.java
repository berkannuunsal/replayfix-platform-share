package com.etiya.replayfix.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record JiraTestTaskResponse(
        UUID caseId,
        String jiraKey,
        boolean previewOnly,
        boolean created,
        String createdIssueKey,
        String createdIssueUrl,
        String parentJiraKey,
        String issueType,
        String summary,
        String descriptionPreview,
        Map<String, Object> jiraPayloadPreview,
        List<String> blockers,
        List<String> warnings,
        List<String> nextActions,
        Instant generatedAt
) {
}
