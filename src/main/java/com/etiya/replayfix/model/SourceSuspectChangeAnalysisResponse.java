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
}
