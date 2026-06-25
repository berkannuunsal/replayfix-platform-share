package com.etiya.replaylab.api.dto;

public record RecommendedCodeChange(
        String file,
        String methodName,
        String changeType,
        String description,
        String pseudoPatch
) {
}
