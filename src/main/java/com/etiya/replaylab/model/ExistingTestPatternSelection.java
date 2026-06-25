package com.etiya.replaylab.model;

import java.util.List;
import java.util.UUID;

public record ExistingTestPatternSelection(
        UUID caseId,
        String repositorySlug,
        String workspace,
        String targetProductionClass,
        String targetProductionMethod,
        String targetPackage,
        int scannedFileCount,
        int matchedFileCount,
        TestPatternCandidate selected,
        List<TestPatternCandidate> alternatives,
        List<String> searchTerms,
        List<String> warnings
) {
}
