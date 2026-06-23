package com.etiya.replayfix.api.dto;

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
