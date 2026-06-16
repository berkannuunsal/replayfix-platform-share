package com.etiya.replayfix.model;

import java.util.List;
import java.util.UUID;

public record JiraEvidenceCommentPublishResult(
        boolean success,
        UUID caseId,
        String issueKey,
        UUID previewEvidenceId,
        UUID approvalId,
        UUID publicationRecordId,
        String jiraCommentId,
        String contentSha256,
        String status,
        List<String> warnings,
        String errorMessage
) {
}
