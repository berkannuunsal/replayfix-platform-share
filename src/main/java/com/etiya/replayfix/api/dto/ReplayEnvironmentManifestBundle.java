package com.etiya.replayfix.api.dto;

import java.util.List;
import java.util.Map;

public record ReplayEnvironmentManifestBundle(
        String bundleFormat,
        boolean dryRunOnly,
        Map<String, Object> namespaceManifest,
        List<Map<String, Object>> argoCdApplicationManifests,
        List<Map<String, Object>> mockServerManifests,
        Map<String, Object> configOverrideManifest,
        Map<String, Object> networkPolicyIntent,
        Map<String, Object> ttlCleanupMetadata
) {
}
