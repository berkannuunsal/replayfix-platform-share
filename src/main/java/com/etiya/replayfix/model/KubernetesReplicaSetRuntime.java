package com.etiya.replayfix.model;

import java.util.List;
import java.util.Map;

public record KubernetesReplicaSetRuntime(
        String namespace,
        String replicaSetName,
        String deploymentName,
        String revision,
        String creationTimestamp,
        int desiredReplicas,
        int readyReplicas,
        List<String> images,
        Map<String, String> annotations,
        Map<String, String> labels
) {
}
