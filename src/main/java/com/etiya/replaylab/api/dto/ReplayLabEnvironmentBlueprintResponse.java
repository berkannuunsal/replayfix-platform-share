package com.etiya.replaylab.api.dto;

import java.util.List;
import java.util.UUID;

public record ReplayLabEnvironmentBlueprintResponse(
        UUID caseId,
        boolean dryRun,
        String status,
        List<String> requiredComponents,
        List<String> optionalComponents,
        List<String> dependencyOrder,
        String deploymentRecipe,
        List<String> healthChecks,
        List<String> mockRequirements,
        List<String> dataPrerequisites,
        String teardownPolicy,
        boolean approvalRequired,
        boolean provisioningExecuted,
        List<String> guardrails,
        List<String> planningEvents
) {
}
