package com.etiya.replayfix.model;

import java.util.List;

public record SourceSuspectCandidateFile(
        String relativePath,
        String fileType,
        List<String> matchedSignals,
        int matchCount,
        List<SourceSuspectSnippet> snippets,
        String className,
        List<SourceSuspectCandidateMethod> candidateMethods,
        String confidence,
        List<String> warnings
) {
}
