package com.etiya.replaylab.api.dto;

import java.util.List;
import java.util.Map;

public record ReplayArgoCdInventoryContext(
        String argocdProject,
        String clusterName,
        String destinationServer,
        List<String> existingApplicationNames,
        Map<String, Object> rawHints
) {
}
