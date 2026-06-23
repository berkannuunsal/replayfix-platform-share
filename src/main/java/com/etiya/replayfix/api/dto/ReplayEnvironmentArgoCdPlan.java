package com.etiya.replayfix.api.dto;

public record ReplayEnvironmentArgoCdPlan(
        String namespace,
        String backendApplicationName,
        String customerUiApplicationName,
        String mockApplicationName,
        String destinationCluster,
        String destinationNamespace,
        String syncPolicy,
        boolean dryRunOnly
) {
}
