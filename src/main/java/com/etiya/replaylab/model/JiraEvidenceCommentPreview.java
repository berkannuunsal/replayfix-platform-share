package com.etiya.replaylab.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

public record JiraEvidenceCommentPreview(
        UUID caseId,
        String issueKey,
        UUID previewEvidenceId,
        String contentSha256,
        String plainTextPreview,
        JsonNode adfBody,
        int plainTextLength,
        boolean sanitized,
        boolean approved,
        boolean published,
        List<String> warnings
) {
}
