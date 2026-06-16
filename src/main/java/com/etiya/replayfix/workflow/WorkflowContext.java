package com.etiya.replayfix.workflow;

import java.util.UUID;

public record WorkflowContext(
        UUID caseId,
        UUID workflowRunId,
        String issueKey,
        String triggerType
) {
}
