package com.etiya.replayfix.api.dto;

import java.util.List;

public record ReplayEnvironmentLlmAdvisoryRequest(
        String question,
        List<String> focusAreas,
        Boolean includeRuntimeDependencies,
        Boolean includeProvisionReadiness,
        Boolean includeDemoSummary
) {
    public ReplayEnvironmentLlmAdvisoryRequest {
        focusAreas = focusAreas == null ? List.of() : List.copyOf(focusAreas);
    }
}
