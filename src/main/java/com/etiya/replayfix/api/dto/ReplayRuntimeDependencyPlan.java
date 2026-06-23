package com.etiya.replayfix.api.dto;

import java.util.List;

public record ReplayRuntimeDependencyPlan(
        String dependencyName,
        String dependencyType,
        String mode,
        String configKey,
        List<String> requiredConfigKeys,
        List<String> requiredSecretKeys,
        boolean productionAccessAllowed,
        boolean productionWriteAllowed,
        boolean test2AccessAllowed,
        boolean test2WriteAllowed,
        boolean test2WriteRequiresApproval,
        boolean connectionRequired,
        boolean consumerEnabled,
        boolean producerEnabled,
        boolean startupRequired,
        boolean replaySafe,
        List<String> configOverrides,
        List<String> blockers,
        List<String> warnings,
        List<String> nextActions
) {
}
