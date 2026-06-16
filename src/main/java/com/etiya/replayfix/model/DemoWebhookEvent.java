package com.etiya.replayfix.model;

import java.time.Instant;

public record DemoWebhookEvent(
        Instant receivedAt,
        String signature,
        boolean signatureValid,
        String eventType,
        String notificationId,
        String caseId,
        String jiraKey,
        String title,
        String severity
) {}
