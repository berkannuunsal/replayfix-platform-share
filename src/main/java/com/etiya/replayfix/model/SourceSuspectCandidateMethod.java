package com.etiya.replayfix.model;

import java.util.List;

public record SourceSuspectCandidateMethod(
        String methodName,
        int lineNumber,
        List<String> annotations,
        List<String> matchedSignals
) {
}
