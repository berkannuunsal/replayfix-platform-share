package com.etiya.replayfix.model;

public record KubernetesContainerRuntime(
        String containerName,
        String image,
        String imageTag,
        String imageDigest,
        String imageId,
        boolean ready,
        int restartCount
) {
}
