package com.etiya.replaylab.model;

import com.etiya.replaylab.domain.NotificationStatus;
import com.etiya.replaylab.domain.NotificationType;

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
