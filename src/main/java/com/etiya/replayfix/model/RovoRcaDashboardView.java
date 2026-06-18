package com.etiya.replayfix.model;

import java.util.List;

public record RovoRcaDashboardView(
        String importStatus,
        String rcaStatus,
        String rawHumanReport,
        Double confidence,
        String probableRootCause,
        String executiveSummary,
        List<String> facts,
        List<String> inferences,
        List<String> unknowns,
        EvidenceMatrix evidenceMatrix,
        List<RelatedJiraIssue> relatedJiraIssues,
        List<ConfluenceRef> confluenceReferences,
        List<SuspectedFile> suspectedFiles,
        List<SuspectedClass> suspectedClasses,
        List<SuspectedMethod> suspectedMethods,
        String regressionTestHypothesis,
        String minimumFixDirection,
        List<String> missingEvidence,
        String recommendedNextAction,
        List<String> normalizationWarnings,
        boolean normalized,
        String analysisTimestamp,
        String rawJson,
        String comparisonMessage
) {
    public record EvidenceMatrix(
            MatrixEntry loki,
            MatrixEntry tempo,
            MatrixEntry source
    ) {}

    public record MatrixEntry(
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
                "NOT_IMPORTED",
                null,
                null,  // rawHumanReport
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
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
            String rawJson
    ) {
        // Build evidence matrix
        EvidenceMatrix evidenceMatrix = null;
        if (analysis.evidenceMatrix() != null) {
            evidenceMatrix = new EvidenceMatrix(
                    buildMatrixEntry(analysis.evidenceMatrix().loki()),
                    buildMatrixEntry(analysis.evidenceMatrix().tempo()),
                    buildMatrixEntry(analysis.evidenceMatrix().source())
            );
        }

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
                "IMPORTED",
                analysis.status() != null ? analysis.status() : "HYPOTHESIS",
                rawHumanReport != null ? rawHumanReport : "",
                analysis.confidence(),
                analysis.probableRootCause(),
                analysis.executiveSummary(),
                analysis.facts() != null ? analysis.facts() : List.of(),
                analysis.inferences() != null ? analysis.inferences() : List.of(),
                analysis.unknowns() != null ? analysis.unknowns() : List.of(),
                evidenceMatrix,
                relatedIssues,
                confluenceRefs,
                suspectedFiles,
                suspectedClasses,
                suspectedMethods,
                analysis.regressionTestHypothesis(),
                analysis.minimumFixDirection(),
                analysis.missingEvidence() != null ? analysis.missingEvidence() : List.of(),
                analysis.recommendedNextAction(),
                normalizationWarnings != null ? normalizationWarnings : List.of(),
                normalized,
                analysis.analysisTimestamp(),
                rawJson,
                comparisonMessage
        );
    }

    private static MatrixEntry buildMatrixEntry(RovoRcaAnalysis.EvidenceMatrixEntry entry) {
        if (entry == null) {
            return null;
        }
        return new MatrixEntry(
                entry.status(),
                entry.references() != null ? entry.references() : List.of(),
                entry.reason()
        );
    }

    private static String buildComparisonMessage(RovoRcaAnalysis analysis) {
        boolean hasWeakEvidence = false;
        
        if (analysis.evidenceMatrix() != null) {
            if (isWeak(analysis.evidenceMatrix().loki()) ||
                isWeak(analysis.evidenceMatrix().tempo()) ||
                isWeak(analysis.evidenceMatrix().source())) {
                hasWeakEvidence = true;
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
