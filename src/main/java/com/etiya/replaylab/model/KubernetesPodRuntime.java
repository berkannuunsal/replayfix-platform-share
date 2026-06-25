package com.etiya.replaylab.model;

import java.util.List;
import java.util.Map;

public record KubernetesPodRuntime(
        String namespace,
        String podName,
        String phase,
        String nodeName,
        String podIp,
        String startedAt,
        List<KubernetesContainerRuntime> containers,
        Map<String, String> labels
) {
}
