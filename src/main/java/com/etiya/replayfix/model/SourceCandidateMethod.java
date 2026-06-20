package com.etiya.replayfix.model;

import java.util.List;

public record SourceCandidateMethod(
        String file,
        String className,
        String methodName,
        int startLine,
        int endLine,
        List<String> relatedSignals,
        String snippet
) {
}
