package com.etiya.replayfix.model;

import com.etiya.replayfix.domain.NotificationStatus;
import com.etiya.replayfix.domain.NotificationType;

import java.time.Instant;
import java.util.UUID;

public record NotificationView(
        UUID id,
        UUID caseId,
        UUID workflowRunId,
        String jiraKey,
        NotificationType type,
        NotificationStatus status,
        String title,
        String message,
        String severity,
        String targetUrl,
        Instant createdAt,
        Instant readAt
) {
}
