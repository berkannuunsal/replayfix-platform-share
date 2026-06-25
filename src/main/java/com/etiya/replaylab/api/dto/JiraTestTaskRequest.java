package com.etiya.replaylab.api.dto;

public record JiraTestTaskRequest(
        String requestedBy,
        String parentJiraKey,
        String issueType,
        boolean createAsSubTask,
        String summaryPrefix,
        boolean includeEvidenceSnapshot,
        boolean includeCodeAdvisory,
        boolean includeTestExecutionPlan,
        boolean confirmCreate,
        boolean guardrailsAccepted
) {
}
