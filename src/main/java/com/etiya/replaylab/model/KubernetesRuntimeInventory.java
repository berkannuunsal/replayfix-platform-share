package com.etiya.replaylab.model;

import java.util.List;
import java.util.UUID;

public record KubernetesRuntimeInventory(
        UUID caseId,
        String applicationKey,
        String context,
        String clusterEvidenceLevel,
        List<KubernetesDeploymentRuntime> deployments,
        List<String> warnings
) {
}
