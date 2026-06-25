package com.etiya.replayfix.api.dto;

public record JenkinsValidationSummaryCommentRequest(
        String requestedBy,
        String projectKey,
        String repositorySlug,
        String pullRequestId,
        String pullRequestUrl,
        String jiraKey,
        boolean commentToPr,
        boolean commentToJira,
        boolean confirmComment,
        boolean guardrailsAccepted
) {
}
