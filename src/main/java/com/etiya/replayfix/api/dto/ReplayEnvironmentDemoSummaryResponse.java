package com.etiya.replayfix.api.dto;

import java.util.List;
import java.util.UUID;

public record ReplayEnvironmentDemoSummaryResponse(
        String jiraKey,
        String targetKey,
        UUID caseId,
        UUID requestId,
        String requestStatus,
        String planStatus,
        String readinessStatus,
        String backendReplayApp,
        String customerUiReplayApp,
        String replayNamespace,
        String proposedHost,
        String accessMode,
        String dbStrategy,
        boolean sanitizedInputAttached,
        boolean runtimeDependenciesSafe,
        List<String> blockers,
        List<String> runtimeDependencyBlockers,
        List<String> runtimeDependencyWarnings,
        List<String> guardrails,
        List<String> nextActions,
        String demoNarrative
) {
    public ReplayEnvironmentDemoSummaryResponse(
            String jiraKey,
            String targetKey,
            UUID caseId,
            UUID requestId,
            String requestStatus,
            String planStatus,
            String readinessStatus,
            String backendReplayApp,
            String customerUiReplayApp,
            String replayNamespace,
            String proposedHost,
            String accessMode,
            String dbStrategy,
            boolean sanitizedInputAttached,
            List<String> blockers,
            List<String> guardrails,
            List<String> nextActions,
            String demoNarrative
    ) {
        this(
                jiraKey,
                targetKey,
                caseId,
                requestId,
                requestStatus,
                planStatus,
                readinessStatus,
                backendReplayApp,
                customerUiReplayApp,
                replayNamespace,
                proposedHost,
                accessMode,
                dbStrategy,
                sanitizedInputAttached,
                blockers == null || blockers.isEmpty(),
                blockers,
                List.of(),
                List.of(),
                guardrails,
                nextActions,
                demoNarrative
        );
    }
}
