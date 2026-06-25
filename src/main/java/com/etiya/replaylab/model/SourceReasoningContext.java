package com.etiya.replaylab.model;

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
    public SourceReasoningContext {
        caseInfo = JsonSafeValueSanitizer.safeMap(caseInfo);
        jira = JsonSafeValueSanitizer.safeMap(jira);
        rovoRca = rovoRca == null ? "" : rovoRca;
        flowAnchors = JsonSafeValueSanitizer.safeList(flowAnchors);
        candidateFlowChain = JsonSafeValueSanitizer.safeList(candidateFlowChain);
        candidateMethods = JsonSafeValueSanitizer.safeList(candidateMethods);
        recentCommits = JsonSafeValueSanitizer.safeList(recentCommits);
        diffSnippets = JsonSafeValueSanitizer.safeList(diffSnippets);
        missingEvidence = JsonSafeValueSanitizer.safeList(missingEvidence);
        guardrails = JsonSafeValueSanitizer.safeList(guardrails);
    }
}
