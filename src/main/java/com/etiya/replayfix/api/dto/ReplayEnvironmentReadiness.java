package com.etiya.replayfix.api.dto;

import java.util.List;

public record ReplayEnvironmentReadiness(
        boolean readyForPlanReview,
        boolean readyForHumanApproval,
        boolean readyForArgoCdProvisioning,
        boolean readyForReplayExecution,
        List<String> blockers
) {
}
