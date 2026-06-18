package com.etiya.replayfix.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RovoRcaImportResponse(
        UUID caseId,
        String jiraKey,
        boolean imported,
        UUID evidenceId,
        UUID existingEvidenceId,
        String commentId,
        Double rovoConfidence,
        String rovoStatus,
        String probableRootCause,
        List<String> warnings,
        String error,
        ImportDiagnostics diagnostics,
        boolean normalized,
        List<String> normalizationWarnings
) {
    
    public record ImportDiagnostics(
            int commentsScanned,
            int pagesScanned,
            int markerStartFoundCount,
            int markerEndFoundCount,
            List<String> candidateCommentIds,
            String latestCommentCreatedAt,
            String latestCommentAuthor,
            List<String> detectedBodyFormats,
            List<Integer> normalizedTextLengths,
            String importedCommentId,
            String importedBodyFormat
    ) {}
    public static RovoRcaImportResponse success(
            UUID caseId,
            String jiraKey,
            UUID evidenceId,
            String commentId,
            Double confidence,
            String probableRootCause,
            ImportDiagnostics diagnostics,
            boolean normalized,
            List<String> normalizationWarnings
    ) {
        return new RovoRcaImportResponse(
                caseId,
                jiraKey,
                true,
                evidenceId,
                null,
                commentId,
                confidence,
                "IMPORTED",
                probableRootCause,
                List.of(),
                null,
                diagnostics,
                normalized,
                normalizationWarnings
        );
    }

    public static RovoRcaImportResponse duplicate(
            UUID caseId,
            String jiraKey,
            UUID existingEvidenceId,
            ImportDiagnostics diagnostics
    ) {
        return new RovoRcaImportResponse(
                caseId,
                jiraKey,
                false,
                null,
                existingEvidenceId,
                null,
                null,
                "DUPLICATE",
                null,
                List.of("Rovo RCA already imported"),
                null,
                diagnostics,
                false,
                List.of()
        );
    }

    public static RovoRcaImportResponse notFound(UUID caseId, String jiraKey, ImportDiagnostics diagnostics) {
        return new RovoRcaImportResponse(
                caseId,
                jiraKey,
                false,
                null,
                null,
                null,
                null,
                "NOT_FOUND",
                null,
                List.of(),
                "ROVO_RCA_COMMENT_NOT_FOUND",
                diagnostics,
                false,
                List.of()
        );
    }

    public static RovoRcaImportResponse invalidJson(UUID caseId, String jiraKey, String message, ImportDiagnostics diagnostics) {
        return new RovoRcaImportResponse(
                caseId,
                jiraKey,
                false,
                null,
                null,
                null,
                null,
                "INVALID_JSON",
                null,
                List.of(),
                "INVALID_ROVO_RCA_JSON: " + message,
                diagnostics,
                false,
                List.of()
        );
    }

    public static RovoRcaImportResponse jiraKeyMismatch(UUID caseId, String expectedKey, String actualKey, ImportDiagnostics diagnostics) {
        return new RovoRcaImportResponse(
                caseId,
                expectedKey,
                false,
                null,
                null,
                null,
                null,
                "JIRA_KEY_MISMATCH",
                null,
                List.of("Expected jiraKey=" + expectedKey + " but found " + actualKey),
                "JIRA_KEY_MISMATCH",
                diagnostics,
                false,
                List.of()
        );
    }
    
    public static RovoRcaImportResponse error(UUID caseId, String jiraKey, String error, ImportDiagnostics diagnostics) {
        return new RovoRcaImportResponse(
                caseId,
                jiraKey,
                false,
                null,
                null,
                null,
                null,
                "ERROR",
                null,
                List.of(),
                error,
                diagnostics,
                false,
                List.of()
        );
    }
}
