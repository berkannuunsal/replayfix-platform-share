package com.etiya.replayfix.api.dto;

import java.util.List;

public record ReplayEnvironmentTopologyComponentPlan(
        String componentKey,
        String componentType,
        String replayApplicationName,
        String repository,
        String branch,
        String helmChartPath,
        String valuesPath,
        String imageRepository,
        String requestedMode,
        String readiness,
        List<String> missingConfig,
        List<String> guardrails
) {
    public ReplayEnvironmentTopologyComponentPlan {
        componentKey = safe(componentKey);
        componentType = safe(componentType);
        replayApplicationName = safe(replayApplicationName);
        repository = safe(repository);
        branch = safe(branch);
        helmChartPath = safe(helmChartPath);
        valuesPath = safe(valuesPath);
        imageRepository = safe(imageRepository);
        requestedMode = safe(requestedMode);
        readiness = safe(readiness);
        missingConfig = missingConfig == null ? List.of() : List.copyOf(missingConfig);
        guardrails = guardrails == null ? List.of() : List.copyOf(guardrails);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
