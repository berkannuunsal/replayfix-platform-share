package com.etiya.replaylab.api.dto;

import java.util.Map;

public record ReplayEnvironmentTargetContext(
        String applicationKey,
        String backendRepositorySlug,
        String backendProjectKey,
        String customerUiRepositorySlug,
        String customerUiProjectKey,
        boolean customerUiRequired,
        String defaultBranch,
        Map<String, Object> rawTargetConfig
) {
}
