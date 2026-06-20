package com.etiya.replayfix.model;

import java.util.List;
import java.util.UUID;

public record SourceSuspectScanResponse(
        UUID caseId,
        String jiraKey,
        String repository,
        String branch,
        int signalCount,
        int candidateFileCount,
        int candidateMethodCount,
        List<SourceSuspectCandidateFile> candidateFiles,
        List<String> warnings
) {
}
