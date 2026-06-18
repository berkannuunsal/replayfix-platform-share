package com.etiya.replayfix.model;

import java.util.List;

public record RovoRcaDashboardView(
        String status,
        Double confidence,
        String probableRootCause,
        List<String> facts,
        List<String> inferences,
        List<String> unknowns,
        List<RelatedJiraIssue> relatedJiraIssues,
        List<ConfluenceRef> confluenceReferences,
        List<SuspectedLocation> suspectedCodeLocations,
        String recommendedNextAction,
        String analysisTimestamp
) {
    public record RelatedJiraIssue(
            String key,
            String summary,
            String relationship
    ) {}

    public record ConfluenceRef(
            String title,
            String url,
            String relevance
    ) {}

    public record SuspectedLocation(
            String filePath,
            String className,
            String methodName,
            String reason
    ) {}

    public static RovoRcaDashboardView notAvailable() {
        return new RovoRcaDashboardView(
                "NOT_AVAILABLE",
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null
        );
    }

    public static RovoRcaDashboardView fromAnalysis(RovoRcaAnalysis analysis) {
        List<RelatedJiraIssue> relatedIssues = analysis.relatedJiraIssues() != null
                ? analysis.relatedJiraIssues().stream()
                        .map(ri -> new RelatedJiraIssue(ri.key(), ri.summary(), ri.relationship()))
                        .toList()
                : List.of();

        List<ConfluenceRef> confluenceRefs = analysis.confluenceReferences() != null
                ? analysis.confluenceReferences().stream()
                        .map(cr -> new ConfluenceRef(cr.title(), cr.url(), cr.relevance()))
                        .toList()
                : List.of();

        List<SuspectedLocation> suspectedLocs = analysis.suspectedCodeLocations() != null
                ? analysis.suspectedCodeLocations().stream()
                        .map(sl -> new SuspectedLocation(sl.filePath(), sl.className(), sl.methodName(), sl.reason()))
                        .toList()
                : List.of();

        return new RovoRcaDashboardView(
                "AVAILABLE",
                analysis.confidence(),
                analysis.probableRootCause(),
                analysis.facts() != null ? analysis.facts() : List.of(),
                analysis.inferences() != null ? analysis.inferences() : List.of(),
                analysis.unknowns() != null ? analysis.unknowns() : List.of(),
                relatedIssues,
                confluenceRefs,
                suspectedLocs,
                analysis.recommendedNextAction(),
                analysis.analysisTimestamp()
        );
    }
}
