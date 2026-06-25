package com.etiya.replaylab.model;

import java.util.List;
import java.util.UUID;

public record JiraWebhookAcceptanceResult(
        boolean accepted,
        boolean duplicate,
        String deliveryId,
        String issueKey,
        UUID caseId,
        UUID workflowRunId,
        String workflowStatus,
        List<String> warnings
) {
}
