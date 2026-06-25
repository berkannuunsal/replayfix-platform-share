package com.etiya.replaylab.model;

import java.util.UUID;

public record PatternInformedTestSourceResult(
        UUID caseId,
        String evidenceType,
        String evidenceSource,
        PatternInformedTestSourceCandidate candidate
) {
}
