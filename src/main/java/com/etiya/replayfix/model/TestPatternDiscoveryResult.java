package com.etiya.replayfix.model;

import java.util.UUID;

public record TestPatternDiscoveryResult(
        UUID caseId,
        String evidenceType,
        String evidenceSource,
        ExistingTestPatternSelection selection,
        boolean fileWritten,
        boolean testExecuted
) {
}
