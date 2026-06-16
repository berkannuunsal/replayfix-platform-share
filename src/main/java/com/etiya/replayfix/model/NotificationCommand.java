package com.etiya.replayfix.model;

import com.etiya.replayfix.domain.NotificationType;

import java.util.UUID;

public record NotificationCommand(
        UUID caseId,
        UUID workflowRunId,
        String jiraKey,
        NotificationType type,
        String title,
        String message,
        String severity,
        String targetUrl
) {
}
