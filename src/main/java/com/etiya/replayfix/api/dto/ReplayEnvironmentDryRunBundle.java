package com.etiya.replayfix.api.dto;

import java.util.Map;

public record ReplayEnvironmentDryRunBundle(
        boolean dryRunOnly,
        Map<String, Object> backendArgoCdApplicationManifest,
        Map<String, Object> customerUiArgoCdApplicationManifest,
        Map<String, Object> mockServerManifest,
        Map<String, Object> backendHelmValueOverrides,
        Map<String, Object> customerUiHelmValueOverrides,
        Map<String, Object> mockServerHelmValues,
        Map<String, Object> networkPolicyIntent,
        Map<String, Object> ttlCleanupMetadata
) {
}
