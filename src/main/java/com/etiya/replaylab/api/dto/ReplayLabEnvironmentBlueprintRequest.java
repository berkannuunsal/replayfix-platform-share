package com.etiya.replaylab.api.dto;

import java.util.List;

public record ReplayLabEnvironmentBlueprintRequest(
        String requestedBy,
        Boolean dryRun,
        List<String> requiredComponents,
        List<String> optionalComponents
) {
}
