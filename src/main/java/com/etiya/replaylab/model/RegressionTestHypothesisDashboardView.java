package com.etiya.replaylab.model;

import java.util.List;

public record RegressionTestHypothesisDashboardView(
        String status,
        String testType,
        String targetFlow,
        String targetComponent,
        String probableRootCause,
        Double confidence,
        String failingScenario,
        List<String> suggestedInputs,
        List<String> expectedFailureSignals,
        List<String> assertions,
        List<String> missingEvidence,
        List<String> warnings,
        boolean fileWritten,
        boolean testExecuted,
        boolean humanApprovalRequired
) {
    public static RegressionTestHypothesisDashboardView notAvailable() {
        return new RegressionTestHypothesisDashboardView(
                "NOT_GENERATED",
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                false,
                true
        );
    }

    public static RegressionTestHypothesisDashboardView from(
            RegressionTestHypothesis hypothesis
    ) {
        if (hypothesis == null) {
            return notAvailable();
        }

        return new RegressionTestHypothesisDashboardView(
                hypothesis.status(),
                hypothesis.testType(),
                hypothesis.targetFlow(),
                hypothesis.targetComponent(),
                hypothesis.probableRootCause(),
                hypothesis.confidence(),
                hypothesis.failingScenario(),
                hypothesis.suggestedInputs() == null ? List.of() : hypothesis.suggestedInputs(),
                hypothesis.expectedFailureSignals() == null ? List.of() : hypothesis.expectedFailureSignals(),
                hypothesis.assertions() == null ? List.of() : hypothesis.assertions(),
                hypothesis.missingEvidence() == null ? List.of() : hypothesis.missingEvidence(),
                hypothesis.warnings() == null ? List.of() : hypothesis.warnings(),
                hypothesis.fileWritten(),
                hypothesis.testExecuted(),
                hypothesis.humanApprovalRequired()
        );
    }
}
