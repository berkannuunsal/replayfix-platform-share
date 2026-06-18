package com.etiya.replayfix.model;

import java.util.List;

public record RovoRcaAnalysis(
        String schemaVersion,
        String jiraKey,
        String status,
        Double confidence,
        String probableRootCause,
        String executiveSummary,
        List<String> facts,
        List<String> inferences,
        List<String> unknowns,
        EvidenceMatrix evidenceMatrix,
        List<RelatedIssue> relatedJiraIssues,
        List<ConfluenceReference> confluenceReferences,
        List<SuspectedFile> suspectedFiles,
        List<SuspectedClass> suspectedClasses,
        List<SuspectedMethod> suspectedMethods,
        List<FailureChainItem> probableFailureChain,
        String regressionTestHypothesis,
        String minimumFixDirection,
        List<String> missingEvidence,
        String recommendedNextAction,
        String analysisTimestamp
) {
    public static final String SCHEMA_VERSION = "1.0";

    public record EvidenceMatrix(
            EvidenceMatrixEntry loki,
            EvidenceMatrixEntry tempo,
            EvidenceMatrixEntry source
    ) {}

    public record EvidenceMatrixEntry(
            String status,
            List<String> references,
            String reason
    ) {}

    public record RelatedIssue(
            String jiraKey,
            String reason
    ) {}

    public record ConfluenceReference(
            String title,
            String url,
            String reason
    ) {}

    public record SuspectedFile(
            String path,
            String reason
    ) {}

    public record SuspectedClass(
            String name,
            String reason
    ) {}

    public record SuspectedMethod(
            String name,
            String reason
    ) {}

    public record FailureChainItem(
            int order,
            String classification,
            String statement,
            String service,
            String operation,
            List<String> evidenceReferences
    ) {}
}
