package com.etiya.replayfix.model;

import java.util.List;
import java.util.UUID;

public record RegressionTestPlanResult(
        UUID caseId,
        String evidenceType,
        String evidenceSource,
        RegressionTestPlan plan,
        boolean fileWritten,
        boolean testExecuted,
        List<String> warnings
) {
}
