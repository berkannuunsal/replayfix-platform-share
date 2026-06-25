package com.etiya.replaylab.api.dto;

import java.util.List;

public record CodeChangeAdvisoryRequest(
        String problemSummary,
        String expectedBehavior,
        String actualBehavior,
        String filePath,
        String classOrComponentName,
        String methodName,
        String language,
        String codeSnippet,
        String relatedDtoSnippet,
        String relatedLogSummary,
        List<String> constraints
) {
    public CodeChangeAdvisoryRequest {
        constraints = constraints == null ? List.of() : List.copyOf(constraints);
    }
}
