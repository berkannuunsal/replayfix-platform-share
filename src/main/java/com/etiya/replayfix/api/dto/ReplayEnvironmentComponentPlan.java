package com.etiya.replayfix.api.dto;

import java.util.List;
import java.util.Map;

public record ReplayEnvironmentComponentPlan(
        String componentType,
        String existingArgoCdApplicationName,
        String replayArgoCdApplicationName,
        String sourceRepoUrl,
        String chartPath,
        String targetRevision,
        String valuesFile,
        String namespace,
        String imageRepository,
        String imageTag,
        String healthEndpoint,
        Map<String, Object> helmValueOverrides,
        List<String> configKeysRequired,
        List<String> secretKeysRequired,
        List<String> missingFields
) {
}
