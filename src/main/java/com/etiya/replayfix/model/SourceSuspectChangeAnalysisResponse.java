package com.etiya.replayfix.model;

import java.util.List;
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
        boolean partial
) {
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
    }
}
