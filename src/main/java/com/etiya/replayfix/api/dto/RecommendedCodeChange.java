package com.etiya.replayfix.api.dto;

public record RecommendedCodeChange(
        String file,
        String methodName,
        String changeType,
        String description,
        String pseudoPatch
) {
}
