package com.etiya.replayfix.model;

import java.util.List;

public record FailingRegressionTestDraftDashboardView(
        String status,
        String readiness,
        String testType,
        String proposedRelativePath,
        String proposedClassName,
        String proposedMethodName,
        String language,
        String framework,
        String sourceCode,
        String contentSha256,
        List<String> expectedFailureSignals,
        List<String> assertions,
        List<String> assumptions,
        List<String> warnings,
        boolean fileWritten,
        boolean testExecuted,
        boolean humanApprovalRequired
) {
    public static FailingRegressionTestDraftDashboardView notAvailable() {
        return new FailingRegressionTestDraftDashboardView(
                "NOT_GENERATED",
                null,
                null,
                null,
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
                false,
                false,
                true
        );
    }

    public static FailingRegressionTestDraftDashboardView from(
            FailingRegressionTestDraft draft
    ) {
        if (draft == null) {
            return notAvailable();
        }

        return new FailingRegressionTestDraftDashboardView(
                draft.status(),
                draft.readiness(),
                draft.testType(),
                draft.proposedRelativePath(),
                draft.proposedClassName(),
                draft.proposedMethodName(),
                draft.language(),
                draft.framework(),
                draft.sourceCode(),
                draft.contentSha256(),
                draft.expectedFailureSignals() == null ? List.of() : draft.expectedFailureSignals(),
                draft.assertions() == null ? List.of() : draft.assertions(),
                draft.assumptions() == null ? List.of() : draft.assumptions(),
                draft.warnings() == null ? List.of() : draft.warnings(),
                draft.fileWritten(),
                draft.testExecuted(),
                draft.humanApprovalRequired()
        );
    }
}
