package com.etiya.replayfix.model;

import java.util.List;
import java.util.UUID;

public record RegressionTestHypothesisResult(
        UUID caseId,
        String evidenceType,
        String evidenceSource,
        boolean generated,
        UUID evidenceId,
        UUID existingEvidenceId,
        RegressionTestHypothesis hypothesis,
        List<String> warnings
) {
}
