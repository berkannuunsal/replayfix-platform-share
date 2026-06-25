package com.etiya.replaylab.model;

import java.util.List;

public record RovoRcaAnalysis(
        String schemaVersion,
        String jiraKey,
        String branch,
        String status,
        String executiveSummary,
        String businessImpact,
        String technicalSymptom,
        String affectedFlow,
        String currentStatus,
        List<FailureChainItem> probableFailureChain,
        List<EvidenceMatrixEntry> evidenceMatrix,
        List<SimilarIncident> similarIncidents,
        List<RelatedIssue> relatedJiraIssues,
        List<ConfluenceReference> confluenceReferences,
        String probableRootCause,
        String impactedComponent,
        Double confidence,
        List<String> supportingEvidenceReferences,
        List<String> competingHypotheses,
        List<SuspectedFile> suspectedFiles,
        List<SuspectedClass> suspectedClasses,
        List<SuspectedMethod> suspectedMethods,
        List<String> regressionTestHypothesis,
        List<String> minimumFixDirection,
        List<String> missingEvidence,
        String recommendedNextAction,
        List<String> warnings,
        String analysisTimestamp
) {
    public static final String SCHEMA_VERSION = "1.0";

    public record EvidenceMatrixEntry(
            String category,
            String status,
            List<String> references,
            String reason
    ) {}

    public record SimilarIncident(
            String jiraKey,
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
            Integer order,
            String classification,
            String statement,
            String service,
            String operation,
            List<String> evidenceReferences
    ) {}
}
