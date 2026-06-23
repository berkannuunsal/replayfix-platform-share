package com.etiya.replayfix.api.dto;

import java.util.List;

public record ReplayEnvironmentStateContinuationPlan(
        boolean canContinueFromExistingState,
        String stateSource,
        List<String> requiredBusinessKeys,
        List<String> requiredSanitizedInputs,
        List<String> blockers,
        List<String> nextActions
) {
}
