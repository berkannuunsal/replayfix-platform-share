package com.etiya.replayfix.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

public record RovoRcaDashboardView(
        boolean rovoRcaAvailable,
        String importStatus,
        String rcaStatus,
        Double rovoConfidence,
        String rawHumanReport,
        JsonNode rawRovoJson,
        JsonNode normalizedRovoJson,
        Double confidence,
        String probableRootCause,
        String commentId,
        String commentAuthor,
        String importedAt,
        String executiveSummary,
        List<MatrixEntry> evidenceMatrix,
        List<RelatedJiraIssue> relatedJiraIssues,
        List<ConfluenceRef> confluenceReferences,
        List<SuspectedFile> suspectedFiles,
        List<SuspectedClass> suspectedClasses,
        List<SuspectedMethod> suspectedMethods,
        List<String> regressionTestHypothesis,
        List<String> minimumFixDirection,
        List<String> missingEvidence,
        String recommendedNextAction,
        List<String> normalizationWarnings,
        boolean normalized,
        String analysisTimestamp,
        String rawJson,
        String comparisonMessage
) {
    public record MatrixEntry(
            String category,
            String status,
            List<String> references,
            String reason
    ) {}

    public record RelatedJiraIssue(
            String jiraKey,
            String reason
    ) {}

    public record ConfluenceRef(
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

    public static RovoRcaDashboardView notAvailable() {
        return new RovoRcaDashboardView(
                false,
                "NOT_IMPORTED",
                null,
                null,
                null,  // rawHumanReport
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),  // evidenceMatrix
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),  // regressionTestHypothesis
                List.of(),  // minimumFixDirection
                List.of(),
                null,
                List.of(),
                false,
                null,
                null,
                null
        );
    }

    public static RovoRcaDashboardView fromAnalysis(
            RovoRcaAnalysis analysis,
            boolean normalized,
            List<String> normalizationWarnings,
            String rawHumanReport,
            JsonNode rawRovoJson,
            JsonNode normalizedRovoJson,
            String rawJson,
            String importStatus,
            String commentId,
            String commentAuthor,
            String importedAt
    ) {
        // Build evidence matrix
        List<MatrixEntry> evidenceMatrix = analysis.evidenceMatrix() != null
                ? analysis.evidenceMatrix().stream()
                        .map(e -> new MatrixEntry(e.category(), e.status(), e.references(), e.reason()))
                        .toList()
                : List.of();

        // Build related Jira issues
        List<RelatedJiraIssue> relatedIssues = analysis.relatedJiraIssues() != null
                ? analysis.relatedJiraIssues().stream()
                        .map(ri -> new RelatedJiraIssue(ri.jiraKey(), ri.reason()))
                        .toList()
                : List.of();

        // Build confluence references
        List<ConfluenceRef> confluenceRefs = analysis.confluenceReferences() != null
                ? analysis.confluenceReferences().stream()
                        .map(cr -> new ConfluenceRef(cr.title(), cr.url(), cr.reason()))
                        .toList()
                : List.of();

        // Build suspected files
        List<SuspectedFile> suspectedFiles = analysis.suspectedFiles() != null
                ? analysis.suspectedFiles().stream()
                        .map(sf -> new SuspectedFile(sf.path(), sf.reason()))
                        .toList()
                : List.of();

        // Build suspected classes
        List<SuspectedClass> suspectedClasses = analysis.suspectedClasses() != null
                ? analysis.suspectedClasses().stream()
                        .map(sc -> new SuspectedClass(sc.name(), sc.reason()))
                        .toList()
                : List.of();

        // Build suspected methods
        List<SuspectedMethod> suspectedMethods = analysis.suspectedMethods() != null
                ? analysis.suspectedMethods().stream()
                        .map(sm -> new SuspectedMethod(sm.name(), sm.reason()))
                        .toList()
                : List.of();

        // Build comparison message
        String comparisonMessage = buildComparisonMessage(analysis);

        return new RovoRcaDashboardView(
                true,
                importStatus != null ? importStatus : "IMPORTED",
                analysis.status() != null ? analysis.status() : "HYPOTHESIS",
                analysis.confidence(),
                rawHumanReport != null ? rawHumanReport : "",
                rawRovoJson,
                normalizedRovoJson,
                analysis.confidence(),
                analysis.probableRootCause(),
                commentId,
                commentAuthor,
                importedAt,
                analysis.executiveSummary(),
                evidenceMatrix,
                relatedIssues,
                confluenceRefs,
                suspectedFiles,
                suspectedClasses,
                suspectedMethods,
                analysis.regressionTestHypothesis() != null ? analysis.regressionTestHypothesis() : List.of(),
                analysis.minimumFixDirection() != null ? analysis.minimumFixDirection() : List.of(),
                analysis.missingEvidence() != null ? analysis.missingEvidence() : List.of(),
                analysis.recommendedNextAction(),
                normalizationWarnings != null ? normalizationWarnings : List.of(),
                normalized,
                analysis.analysisTimestamp(),
                rawJson,
                comparisonMessage
        );
    }

    private static String buildComparisonMessage(RovoRcaAnalysis analysis) {
        boolean hasWeakEvidence = false;
        
        if (analysis.evidenceMatrix() != null) {
            for (RovoRcaAnalysis.EvidenceMatrixEntry entry : analysis.evidenceMatrix()) {
                if (isWeak(entry)) {
                    hasWeakEvidence = true;
                    break;
                }
            }
        }

        if (analysis.missingEvidence() != null && !analysis.missingEvidence().isEmpty()) {
            hasWeakEvidence = true;
        }

        if (hasWeakEvidence) {
            return "ReplayFix incident version is verified, but Loki, Tempo and source-context evidence are weak. Rovo RCA is a hypothesis and requires human validation.";
        }

        return "Rovo RCA is enriched with Jira context and organizational knowledge. ReplayFix deterministic RCA is based solely on runtime evidence.";
    }

    private static boolean isWeak(RovoRcaAnalysis.EvidenceMatrixEntry entry) {
        if (entry == null) {
            return true;
        }
        String status = entry.status();
        return status == null || 
               status.equalsIgnoreCase("WEAK") || 
               status.equalsIgnoreCase("MISSING") ||
               status.equalsIgnoreCase("UNAVAILABLE");
    }
}
