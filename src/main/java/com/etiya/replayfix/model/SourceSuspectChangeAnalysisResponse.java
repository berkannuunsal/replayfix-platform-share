package com.etiya.replayfix.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record SourceSuspectChangeAnalysisResponse(
        UUID caseId,
        String jiraKey,
        String repository,
        String branch,
        String incidentCommitSha,
        int lookbackDays,
        List<SourceFlowAnchor> flowAnchors,
        List<SourceCandidateFlowChainItem> candidateFlowChain,
        List<String> candidateFiles,
        List<SourceCandidateMethod> candidateMethods,
        List<SourceRecentCommit> recentCommits,
        SourceReasoningContext sourceReasoningContext,
        boolean llmUsed,
        List<SourceSuspectChange> suspectChanges,
        String status,
        double confidence,
        List<String> warnings,
        String analysisMode,
        boolean partial,
        Map<String, Long> phaseTimingsMs,
        String lastCompletedPhase,
        String currentPhaseOnTimeout,
        int endpointSearchFileCount,
        int controllerCandidateCount,
        int endpointMatchAttempts,
        List<String> matchedEndpointAnchors,
        List<String> unmatchedEndpointAnchors,
        List<SourceDiscoveredControllerEndpoint> discoveredControllerEndpoints,
        int serviceResolutionAttempts,
        List<String> resolvedServiceTypes,
        List<String> resolvedImplementationFiles,
        List<String> unresolvedServiceCalls,
        List<SourceLastCommitDiagnostic> lastCommitDiagnostics,
        int companyLlmTimeoutSeconds,
        long companyLlmElapsedMs,
        String companyLlmStatus,
        int companyLlmPromptChars,
        String companyLlmContextMode,
        int companyLlmMaxPromptChars,
        int companyLlmOutputTokenLimit,
        String companyLlmPromptHash
) {
    public SourceSuspectChangeAnalysisResponse(
            UUID caseId,
            String jiraKey,
            String repository,
            String branch,
            String incidentCommitSha,
            int lookbackDays,
            List<SourceFlowAnchor> flowAnchors,
            List<SourceCandidateFlowChainItem> candidateFlowChain,
            List<String> candidateFiles,
            List<SourceCandidateMethod> candidateMethods,
            List<SourceRecentCommit> recentCommits,
            SourceReasoningContext sourceReasoningContext,
            boolean llmUsed,
            List<SourceSuspectChange> suspectChanges,
            String status,
            double confidence,
            List<String> warnings,
            String analysisMode,
            boolean partial
    ) {
        this(
                caseId,
                jiraKey,
                repository,
                branch,
                incidentCommitSha,
                lookbackDays,
                flowAnchors,
                candidateFlowChain,
                candidateFiles,
                candidateMethods,
                recentCommits,
                sourceReasoningContext,
                llmUsed,
                suspectChanges,
                status,
                confidence,
                warnings,
                analysisMode,
                partial,
                Map.of(),
                "",
                null,
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0,
                0L,
                "NOT_REQUESTED",
                0,
                "COMPACT",
                12000,
                500,
                ""
        );
    }

    public SourceSuspectChangeAnalysisResponse(
            UUID caseId,
            String jiraKey,
            String repository,
            String branch,
            String incidentCommitSha,
            int lookbackDays,
            List<SourceFlowAnchor> flowAnchors,
            List<SourceCandidateFlowChainItem> candidateFlowChain,
            List<String> candidateFiles,
            List<SourceCandidateMethod> candidateMethods,
            List<SourceRecentCommit> recentCommits,
            SourceReasoningContext sourceReasoningContext,
            boolean llmUsed,
            List<SourceSuspectChange> suspectChanges,
            String status,
            double confidence,
            List<String> warnings,
            String analysisMode,
            boolean partial,
            Map<String, Long> phaseTimingsMs,
            String lastCompletedPhase,
            String currentPhaseOnTimeout
    ) {
        this(
                caseId,
                jiraKey,
                repository,
                branch,
                incidentCommitSha,
                lookbackDays,
                flowAnchors,
                candidateFlowChain,
                candidateFiles,
                candidateMethods,
                recentCommits,
                sourceReasoningContext,
                llmUsed,
                suspectChanges,
                status,
                confidence,
                warnings,
                analysisMode,
                partial,
                phaseTimingsMs,
                lastCompletedPhase,
                currentPhaseOnTimeout,
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                0,
                0L,
                "NOT_REQUESTED",
                0,
                "COMPACT",
                12000,
                500,
                ""
        );
    }

    public SourceSuspectChangeAnalysisResponse {
        jiraKey = jiraKey == null ? "" : jiraKey;
        repository = repository == null ? "" : repository;
        branch = branch == null ? "" : branch;
        incidentCommitSha = incidentCommitSha == null ? "" : incidentCommitSha;
        flowAnchors = JsonSafeValueSanitizer.safeList(flowAnchors);
        candidateFlowChain = JsonSafeValueSanitizer.safeList(candidateFlowChain);
        candidateFiles = JsonSafeValueSanitizer.safeList(candidateFiles);
        candidateMethods = JsonSafeValueSanitizer.safeList(candidateMethods);
        recentCommits = JsonSafeValueSanitizer.safeList(recentCommits);
        if (sourceReasoningContext == null) {
            sourceReasoningContext = new SourceReasoningContext(
                    java.util.Map.of(),
                    java.util.Map.of(),
                    "",
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of(),
                    java.util.List.of()
            );
        }
        suspectChanges = JsonSafeValueSanitizer.safeList(suspectChanges);
        status = status == null ? "HYPOTHESIS" : status;
        warnings = JsonSafeValueSanitizer.safeList(warnings);
        analysisMode = analysisMode == null ? "DETERMINISTIC_ONLY" : analysisMode;
        phaseTimingsMs = phaseTimingsMs == null ? Map.of() : Map.copyOf(phaseTimingsMs);
        lastCompletedPhase = lastCompletedPhase == null ? "" : lastCompletedPhase;
        matchedEndpointAnchors = JsonSafeValueSanitizer.safeList(matchedEndpointAnchors);
        unmatchedEndpointAnchors = JsonSafeValueSanitizer.safeList(unmatchedEndpointAnchors);
        discoveredControllerEndpoints =
                JsonSafeValueSanitizer.safeList(discoveredControllerEndpoints);
        resolvedServiceTypes = JsonSafeValueSanitizer.safeList(resolvedServiceTypes);
        resolvedImplementationFiles =
                JsonSafeValueSanitizer.safeList(resolvedImplementationFiles);
        unresolvedServiceCalls = JsonSafeValueSanitizer.safeList(unresolvedServiceCalls);
        lastCommitDiagnostics = JsonSafeValueSanitizer.safeList(lastCommitDiagnostics);
        companyLlmStatus = companyLlmStatus == null
                ? "NOT_REQUESTED"
                : companyLlmStatus;
        companyLlmContextMode = companyLlmContextMode == null
                ? "COMPACT"
                : companyLlmContextMode;
        companyLlmPromptHash = companyLlmPromptHash == null
                ? ""
                : companyLlmPromptHash;
    }
}
