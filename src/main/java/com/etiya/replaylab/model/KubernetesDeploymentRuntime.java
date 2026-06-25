package com.etiya.replaylab.model;

import java.util.List;
import java.util.Map;

public record KubernetesDeploymentRuntime(
        String namespace,
        String deploymentName,
        String creationTimestamp,
        String revision,
        long generation,
        int desiredReplicas,
        int availableReplicas,
        List<String> images,
        Map<String, String> annotations,
        Map<String, String> labels,
        List<KubernetesReplicaSetRuntime> replicaSets,
        List<KubernetesPodRuntime> pods
) {
}
