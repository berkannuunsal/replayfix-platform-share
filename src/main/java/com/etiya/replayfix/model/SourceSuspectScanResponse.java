package com.etiya.replayfix.model;

import java.util.List;
import java.util.Map;
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
        List<String> warnings,
        String scannedRoot,
        boolean workspaceResolved,
        int scannedFileCount,
        int scannedDirectoryCount,
        int skippedDirectoryCount,
        Map<String, Integer> fileExtensionCounts,
        int searchedSignalCount,
        List<String> usedSignalsPreview,
        String repositorySlug,
        String repositoryName
) {
}
