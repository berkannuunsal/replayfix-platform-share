package com.etiya.replayfix.api.dto;

import java.util.List;

public record ReplayEnvironmentDbStrategyPlan(
        String strategy,
        boolean backendCanStart,
        boolean stateContinuationSupported,
        boolean productionWriteAllowed,
        boolean test2WriteRisk,
        boolean readOnlyUserRequired,
        boolean cloneDbRequired,
        List<String> requiredSecretKeys,
        List<String> requiredDbInputs,
        List<String> blockers,
        List<String> nextActions
) {
}
