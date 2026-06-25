package com.etiya.replaylab.model;

import java.util.Map;

public record JiraWebhookEvent(
        String webhookEvent,
        String issueKey,
        String issueId,
        String projectKey,
        String issueType,
        String status,
        String summary,
        String actorAccountId,
        String eventTimestamp,
        String bodySha256,
        String deliveryId,
        Map<String, Object> changedFields
) {
}
