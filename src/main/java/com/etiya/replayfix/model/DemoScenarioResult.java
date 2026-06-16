package com.etiya.replayfix.model;

import java.util.List;
import java.util.UUID;

public record DemoScenarioResult(
        UUID caseId,
        UUID workflowRunId,
        String issueKey,
        String dashboardUrl,
        boolean synthetic,
        String status,
        List<String> warnings
) {
}
