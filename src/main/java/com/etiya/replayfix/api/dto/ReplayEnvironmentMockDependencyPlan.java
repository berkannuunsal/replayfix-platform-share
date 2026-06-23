package com.etiya.replayfix.api.dto;

import java.util.List;
import java.util.Map;

public record ReplayEnvironmentMockDependencyPlan(
        String dependencyName,
        String dependencyType,
        String configKey,
        String originalValueMasked,
        String replayMockUrl,
        String mockMode,
        String responseSource,
        List<String> requiredSampleData,
        List<String> missingFields,
        Map<String, Object> sampleResponseTemplate
) {
}
