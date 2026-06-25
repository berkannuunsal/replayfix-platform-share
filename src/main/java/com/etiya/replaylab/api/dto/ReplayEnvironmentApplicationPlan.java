package com.etiya.replaylab.api.dto;

import java.util.Map;

public record ReplayEnvironmentApplicationPlan(
        String name,
        String componentType,
        String repositoryUrl,
        String path,
        String targetRevision,
        String imageTag,
        Map<String, Object> helmValues,
        Map<String, Object> configOverrides
) {
}
