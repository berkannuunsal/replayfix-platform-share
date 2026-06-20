package com.etiya.replayfix.model;

import java.util.List;

public record SourceSuspectChange(
        String file,
        String className,
        String methodName,
        String layer,
        String relatedFlow,
        List<String> relatedSignals,
        int recentCommitCount,
        List<SourceRecentCommit> recentCommits,
        String suspectReason,
        double confidence,
        String status,
        List<String> warnings
) {
}
