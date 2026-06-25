package com.etiya.replaylab.model;

import java.util.List;

public record SourceDiffSnippet(
        String commitSha,
        String file,
        String methodName,
        String diff,
        List<String> warnings
) {
}
