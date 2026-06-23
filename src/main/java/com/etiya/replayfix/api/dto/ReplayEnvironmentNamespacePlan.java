package com.etiya.replayfix.api.dto;

import java.util.List;

public record ReplayEnvironmentNamespacePlan(
        String strategy,
        String existingNamespace,
        String proposedReplayNamespace,
        boolean namespaceCreationAllowed,
        boolean iacRequired,
        List<String> blockers
) {
}
