package com.etiya.replayfix.api.dto;

public record ReplayEnvironmentTopologyDependencyPlan(
        String componentKey,
        String dependencyMode,
        String description
) {
    public ReplayEnvironmentTopologyDependencyPlan {
        componentKey = componentKey == null ? "" : componentKey;
        dependencyMode = dependencyMode == null ? "" : dependencyMode;
        description = description == null ? "" : description;
    }
}
