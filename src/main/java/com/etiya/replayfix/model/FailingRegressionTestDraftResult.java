package com.etiya.replayfix.model;

import java.util.List;
import java.util.UUID;

public record FailingRegressionTestDraftResult(
        UUID caseId,
        String evidenceType,
        String evidenceSource,
        boolean generated,
        UUID evidenceId,
        UUID existingEvidenceId,
        FailingRegressionTestDraft draft,
        List<String> warnings
) {
}
