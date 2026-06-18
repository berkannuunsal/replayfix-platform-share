package com.etiya.replayfix.model;

import java.util.List;

public record RovoRcaAnalysis(
        String schemaVersion,
        String jiraKey,
        String probableRootCause,
        double confidence,
        List<String> facts,
        List<String> inferences,
        List<String> unknowns,
        List<RelatedIssue> relatedJiraIssues,
        List<ConfluenceReference> confluenceReferences,
        List<SuspectedCodeLocation> suspectedCodeLocations,
        String recommendedNextAction,
        String analysisTimestamp
) {
    public static final String SCHEMA_VERSION = "1.0";

    public record RelatedIssue(
            String key,
            String summary,
            String relationship
    ) {}

    public record ConfluenceReference(
            String title,
            String url,
            String relevance
    ) {}

    public record SuspectedCodeLocation(
            String filePath,
            String className,
            String methodName,
            String reason
    ) {}
}
