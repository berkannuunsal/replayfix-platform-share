package com.etiya.replayfix.model;

import java.util.List;
import java.util.Map;

public record SourceReasoningContext(
        Map<String, Object> caseInfo,
        Map<String, Object> jira,
        String rovoRca,
        List<SourceFlowAnchor> flowAnchors,
        List<SourceCandidateFlowChainItem> candidateFlowChain,
        List<SourceCandidateMethod> candidateMethods,
        List<SourceRecentCommit> recentCommits,
        List<SourceDiffSnippet> diffSnippets,
        List<String> missingEvidence,
        List<String> guardrails
) {
}
